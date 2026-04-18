// appflow_like_refined.c
// macOS user-space AppFlow-like scheduler with:
//
// 1. Real-time refresh of actual app state
// 2. Distinction between "preloaded" and "actually used"
// 3. Manual-close suppression to avoid relaunching apps the user just closed
//
// Compile:
//   clang -O2 -Wall -Wextra appflow_like_refined.c -o appflow_like_refined
//
// Run:
//   ./appflow_like_refined
//
// Example:
//   predict Calendar 0.95
//   predict "Google Chrome" 0.90
//   predict Mail 0.75
//   status
//   launch Calendar
//   status
//   quit

#define _POSIX_C_SOURCE 200809L

#include <ctype.h>
#include <errno.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <strings.h>
#include <sys/select.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

#define MAX_APPS 128
#define MAX_NAME 256
#define LINE_BUF 512

#define DEFAULT_BUDGET 3
#define DEFAULT_MIN_SCORE 0.50
#define PROTECT_SEC 120
#define TICK_SEC 1

// If the user manually closes a preloaded app, do not preload it again immediately.
#define MANUAL_CLOSE_COOLDOWN_SEC 180

typedef struct
{
    char name[MAX_NAME];

    double score;
    time_t predicted_at;
    time_t last_used_at;
    time_t last_launch_at;

    // Real observed state from macOS.
    bool running;

    // Scheduler bookkeeping:
    // launched_by_us: we launched this app at some point
    // in_preload_pool: currently counts against preload budget
    // user_loaded: user has actually used / foregrounded it, so it no longer counts
    bool launched_by_us;
    bool in_preload_pool;
    bool user_loaded;

    // If user manually closed a preloaded app, suppress re-preloading for a while.
    time_t suppressed_until;
} AppEntry;

typedef struct
{
    AppEntry apps[MAX_APPS];
    size_t count;
    int budget;
    double min_score;
    bool running;
} AppTable;

static AppTable g = {
    .count = 0,
    .budget = DEFAULT_BUDGET,
    .min_score = DEFAULT_MIN_SCORE,
    .running = true};

static void trim_newline(char *s)
{
    size_t n = strlen(s);
    while (n > 0 && (s[n - 1] == '\n' || s[n - 1] == '\r'))
    {
        s[n - 1] = '\0';
        n--;
    }
}

static void skip_spaces(const char **p)
{
    while (**p && isspace((unsigned char)**p))
    {
        (*p)++;
    }
}

static bool parse_app_name(const char **p, char *out, size_t out_sz)
{
    skip_spaces(p);
    if (**p == '\0')
        return false;

    size_t i = 0;
    if (**p == '"')
    {
        (*p)++;
        while (**p && **p != '"' && i + 1 < out_sz)
        {
            out[i++] = **p;
            (*p)++;
        }
        if (**p == '"')
            (*p)++;
    }
    else
    {
        while (**p && !isspace((unsigned char)**p) && i + 1 < out_sz)
        {
            out[i++] = **p;
            (*p)++;
        }
    }
    out[i] = '\0';
    return i > 0;
}

static AppEntry *find_app(const char *name)
{
    for (size_t i = 0; i < g.count; i++)
    {
        if (strcasecmp(g.apps[i].name, name) == 0)
        {
            return &g.apps[i];
        }
    }
    return NULL;
}

static AppEntry *get_or_create_app(const char *name)
{
    AppEntry *e = find_app(name);
    if (e)
        return e;

    if (g.count >= MAX_APPS)
        return NULL;

    e = &g.apps[g.count++];
    memset(e, 0, sizeof(*e));
    snprintf(e->name, sizeof(e->name), "%s", name);
    return e;
}

static bool recently_used(const AppEntry *e, time_t now)
{
    return e->last_used_at != 0 && (now - e->last_used_at) <= PROTECT_SEC;
}

static bool suppressed(const AppEntry *e, time_t now)
{
    return e->suppressed_until != 0 && now < e->suppressed_until;
}

static int spawn_and_wait(char *const argv[])
{
    pid_t pid = fork();
    if (pid < 0)
        return -1;

    if (pid == 0)
    {
        execvp(argv[0], argv);
        _exit(127);
    }

    int status = 0;
    if (waitpid(pid, &status, 0) < 0)
        return -1;
    return status;
}

static int preload_hidden(const char *app)
{
    char *const argv[] = {"open", "-g", "-a", (char *)app, NULL};
    return spawn_and_wait(argv);
}

static int launch_foreground(const char *app)
{
    char *const argv[] = {"open", "-a", (char *)app, NULL};
    return spawn_and_wait(argv);
}

static int quit_gracefully(const char *app)
{
    char script[512];
    snprintf(script, sizeof(script), "tell application \"%s\" to quit", app);
    char *const argv[] = {"osascript", "-e", script, NULL};
    return spawn_and_wait(argv);
}

static bool is_app_running_os(const char *app)
{
    char cmd[1024];
    snprintf(
        cmd, sizeof(cmd),
        "osascript -e 'tell application \"System Events\" to "
        "((name of processes) contains \"%s\")' 2>/dev/null",
        app);

    FILE *fp = popen(cmd, "r");
    if (!fp)
        return false;

    char buf[64] = {0};
    if (!fgets(buf, sizeof(buf), fp))
    {
        pclose(fp);
        return false;
    }
    pclose(fp);

    return strncasecmp(buf, "true", 4) == 0;
}

static bool is_frontmost_os(const char *app)
{
    char cmd[1024];
    snprintf(
        cmd, sizeof(cmd),
        "osascript -e 'tell application \"System Events\" to "
        "name of first application process whose frontmost is true' 2>/dev/null");

    FILE *fp = popen(cmd, "r");
    if (!fp)
        return false;

    char buf[256] = {0};
    if (!fgets(buf, sizeof(buf), fp))
    {
        pclose(fp);
        return false;
    }
    pclose(fp);

    trim_newline(buf);
    return strcasecmp(buf, app) == 0;
}

// Refresh real OS state and reconcile scheduler state.
static void refresh_real_state(void)
{
    time_t now = time(NULL);

    for (size_t i = 0; i < g.count; i++)
    {
        AppEntry *e = &g.apps[i];
        bool was_running = e->running;
        bool now_running = is_app_running_os(e->name);

        e->running = now_running;

        // If the app was running before and now is gone, treat it as a user-close event.
        // This applies to BOTH:
        //   1. preloaded apps
        //   2. actually user-loaded apps
        //
        // User intent wins: clear scheduler ownership and zero the score so it will
        // not be re-preloaded unless a NEW prediction arrives later.
        if (was_running && !now_running)
        {
            bool was_preloaded = e->in_preload_pool;
            bool was_user_loaded = e->user_loaded;

            e->in_preload_pool = false;
            e->user_loaded = false;
            e->launched_by_us = false;

            e->score = 0.0;
            e->suppressed_until = now + MANUAL_CLOSE_COOLDOWN_SEC;

            if (was_preloaded || was_user_loaded)
            {
                printf("[closed] %s removed from scheduler control, score reset to 0, suppressed for %d sec\n",
                       e->name, MANUAL_CLOSE_COOLDOWN_SEC);
            }

            continue;
        }

        // If not running, keep it out of all active pools.
        if (!now_running)
        {
            e->in_preload_pool = false;
            e->user_loaded = false;
            e->launched_by_us = false;
            continue;
        }

        // If running and frontmost, treat it as actually used by the user.
        // Once actually used, it no longer counts against preload budget.
        if (is_frontmost_os(e->name))
        {
            if (e->in_preload_pool)
            {
                printf("[activate] %s moved from preload pool to user-loaded\n", e->name);
            }
            e->user_loaded = true;
            e->in_preload_pool = false;
            e->last_used_at = now;
        }
    }
}

static int current_budget_used(void)
{
    int n = 0;
    for (size_t i = 0; i < g.count; i++)
    {
        if (g.apps[i].running && g.apps[i].in_preload_pool)
        {
            n++;
        }
    }
    return n;
}

static double effective_priority(const AppEntry *e, time_t now)
{
    double p = e->score;

    if (recently_used(e, now))
        p += 1000.0;
    if (e->predicted_at != 0)
        p += 0.000001 * (double)e->predicted_at;

    return p;
}

static AppEntry *best_candidate_to_preload(time_t now)
{
    AppEntry *best = NULL;
    double best_p = -1e30;

    for (size_t i = 0; i < g.count; i++)
    {
        AppEntry *e = &g.apps[i];

        if (e->running)
            continue;
        if (e->score < g.min_score)
            continue;
        if (suppressed(e, now))
            continue;

        double p = effective_priority(e, now);
        if (!best || p > best_p)
        {
            best = e;
            best_p = p;
        }
    }
    return best;
}

static AppEntry *worst_evictable_preloaded_app(time_t now)
{
    AppEntry *worst = NULL;
    double worst_p = 1e30;

    for (size_t i = 0; i < g.count; i++)
    {
        AppEntry *e = &g.apps[i];

        if (!e->running)
            continue;
        if (!e->in_preload_pool)
            continue;
        if (recently_used(e, now))
            continue;

        double p = effective_priority(e, now);
        if (!worst || p < worst_p)
        {
            worst = e;
            worst_p = p;
        }
    }
    return worst;
}

static void scheduler_pass(void)
{
    refresh_real_state();

    time_t now = time(NULL);

    while (1)
    {
        int used = current_budget_used();
        AppEntry *cand = best_candidate_to_preload(now);
        if (!cand)
            break;

        if (used < g.budget)
        {
            int rc = preload_hidden(cand->name);
            refresh_real_state();

            if (rc == 0 && cand->running)
            {
                cand->last_launch_at = time(NULL);
                cand->launched_by_us = true;
                cand->in_preload_pool = true;
                cand->user_loaded = false;
                printf("[preload] %s\n", cand->name);
            }
            else
            {
                printf("[preload-failed] %s\n", cand->name);
                cand->score = 0.0;
            }
            continue;
        }

        AppEntry *victim = worst_evictable_preloaded_app(now);
        if (!victim)
            break;

        double cand_p = effective_priority(cand, now);
        double victim_p = effective_priority(victim, now);

        if (cand_p <= victim_p)
            break;

        printf("[replace] evict %s for %s\n", victim->name, cand->name);
        int qrc = quit_gracefully(victim->name);
        refresh_real_state();

        if (qrc != 0)
        {
            printf("[evict-failed] %s\n", victim->name);
            break;
        }
    }
}

static void print_help(void)
{
    printf("Commands:\n");
    printf("  predict <app> <score>\n");
    printf("  predict \"Google Chrome\" 0.90\n");
    printf("  use <app>               mark user-used now\n");
    printf("  launch <app>            open in foreground now\n");
    printf("  budget <n>\n");
    printf("  minscore <x>\n");
    printf("  refresh\n");
    printf("  status\n");
    printf("  help\n");
    printf("  quit\n");
}

static void print_status(void)
{
    refresh_real_state();
    time_t now = time(NULL);

    printf("---- status ----\n");
    printf("budget=%d used=%d minscore=%.2f tracked=%zu\n",
           g.budget, current_budget_used(), g.min_score, g.count);

    for (size_t i = 0; i < g.count; i++)
    {
        AppEntry *e = &g.apps[i];
        printf("%-24s run=%-3s preload=%-3s used=%-3s score=%.2f recent=%-3s suppressed=%-3s\n",
               e->name,
               e->running ? "yes" : "no",
               e->in_preload_pool ? "yes" : "no",
               e->user_loaded ? "yes" : "no",
               e->score,
               recently_used(e, now) ? "yes" : "no",
               suppressed(e, now) ? "yes" : "no");
    }
    printf("----------------\n");
}

static void handle_predict(const char *app, double score)
{
    AppEntry *e = get_or_create_app(app);
    if (!e)
    {
        printf("[error] app table full\n");
        return;
    }

    e->score = score;
    e->predicted_at = time(NULL);

    printf("[predict] %s score=%.2f\n", app, score);
    scheduler_pass();
}

static void handle_use(const char *app)
{
    AppEntry *e = get_or_create_app(app);
    if (!e)
    {
        printf("[error] app table full\n");
        return;
    }

    e->last_used_at = time(NULL);
    refresh_real_state();

    if (e->running)
    {
        e->user_loaded = true;
        e->in_preload_pool = false;
        e->launched_by_us = true;
        printf("[use] %s marked as user-loaded\n", app);
    }
    else
    {
        printf("[use] %s marked recent (not running now)\n", app);
    }

    scheduler_pass();
}

static void handle_launch(const char *app)
{
    AppEntry *e = get_or_create_app(app);
    if (!e)
    {
        printf("[error] app table full\n");
        return;
    }

    int rc = launch_foreground(app);
    refresh_real_state();

    if (rc == 0 && e->running)
    {
        e->last_launch_at = time(NULL);
        e->last_used_at = time(NULL);
        e->launched_by_us = true;
        e->user_loaded = true;
        e->in_preload_pool = false;
        printf("[launch] %s now user-loaded\n", app);
    }
    else
    {
        printf("[launch-failed] %s\n", app);
    }

    scheduler_pass();
}

static void handle_budget(int n)
{
    if (n <= 0)
    {
        printf("[budget] must be > 0\n");
        return;
    }
    g.budget = n;
    printf("[budget] set to %d\n", n);
    scheduler_pass();
}

static void handle_minscore(double x)
{
    if (x < 0.0 || x > 1.0)
    {
        printf("[minscore] must be in [0,1]\n");
        return;
    }
    g.min_score = x;
    printf("[minscore] set to %.2f\n", x);
    scheduler_pass();
}

static void process_line(char *line)
{
    trim_newline(line);

    const char *p = line;
    skip_spaces(&p);
    if (*p == '\0')
        return;

    char cmd[64] = {0};
    size_t i = 0;
    while (*p && !isspace((unsigned char)*p) && i + 1 < sizeof(cmd))
    {
        cmd[i++] = *p++;
    }
    cmd[i] = '\0';

    if (strcasecmp(cmd, "quit") == 0 || strcasecmp(cmd, "exit") == 0)
    {
        g.running = false;
        return;
    }

    if (strcasecmp(cmd, "help") == 0)
    {
        print_help();
        return;
    }

    if (strcasecmp(cmd, "status") == 0)
    {
        print_status();
        return;
    }

    if (strcasecmp(cmd, "refresh") == 0)
    {
        refresh_real_state();
        scheduler_pass();
        printf("[refresh]\n");
        return;
    }

    if (strcasecmp(cmd, "predict") == 0)
    {
        char app[MAX_NAME];
        if (!parse_app_name(&p, app, sizeof(app)))
        {
            printf("usage: predict <app> <score>\n");
            return;
        }
        skip_spaces(&p);
        char *end = NULL;
        double score = strtod(p, &end);
        if (end == p)
        {
            printf("usage: predict <app> <score>\n");
            return;
        }
        handle_predict(app, score);
        return;
    }

    if (strcasecmp(cmd, "use") == 0)
    {
        char app[MAX_NAME];
        if (!parse_app_name(&p, app, sizeof(app)))
        {
            printf("usage: use <app>\n");
            return;
        }
        handle_use(app);
        return;
    }

    if (strcasecmp(cmd, "launch") == 0)
    {
        char app[MAX_NAME];
        if (!parse_app_name(&p, app, sizeof(app)))
        {
            printf("usage: launch <app>\n");
            return;
        }
        handle_launch(app);
        return;
    }

    if (strcasecmp(cmd, "budget") == 0)
    {
        skip_spaces(&p);
        int n = atoi(p);
        handle_budget(n);
        return;
    }

    if (strcasecmp(cmd, "minscore") == 0)
    {
        skip_spaces(&p);
        double x = atof(p);
        handle_minscore(x);
        return;
    }

    printf("unknown command: %s\n", cmd);
    print_help();
}

int main(void)
{
    printf("AppFlow-like refined scheduler (macOS user space)\n");
    printf("Manual predictions, real-time app-state reconciliation.\n");
    print_help();

    while (g.running)
    {
        fd_set rfds;
        FD_ZERO(&rfds);
        FD_SET(STDIN_FILENO, &rfds);

        struct timeval tv;
        tv.tv_sec = TICK_SEC;
        tv.tv_usec = 0;

        int ret = select(STDIN_FILENO + 1, &rfds, NULL, NULL, &tv);
        if (ret < 0)
        {
            if (errno == EINTR)
                continue;
            perror("select");
            break;
        }

        if (ret == 0)
        {
            scheduler_pass();
            continue;
        }

        if (FD_ISSET(STDIN_FILENO, &rfds))
        {
            char line[LINE_BUF];
            if (!fgets(line, sizeof(line), stdin))
                break;
            process_line(line);
        }
    }

    printf("Bye.\n");
    return 0;
}