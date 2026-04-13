#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <ctype.h>

#define MAX_INPUT 512

static void trim_newline(char *s)
{
    size_t n = strlen(s);
    if (n > 0 && s[n - 1] == '\n')
    {
        s[n - 1] = '\0';
    }
}

static int is_blank(const char *s)
{
    while (*s)
    {
        if (!isspace((unsigned char)*s))
        {
            return 0;
        }
        s++;
    }
    return 1;
}

int main(void)
{
    char input[MAX_INPUT];

    printf("Interactive app preloader (macOS)\n");
    printf("Type an app name such as Calendar, Mail, or \"Google Chrome\".\n");
    printf("Type 'exit' or 'quit' to stop.\n\n");

    while (1)
    {
        printf("app> ");
        fflush(stdout);

        if (fgets(input, sizeof(input), stdin) == NULL)
        {
            printf("\nEnd of input. Exiting.\n");
            break;
        }

        trim_newline(input);

        if (is_blank(input))
        {
            continue;
        }

        if (strcmp(input, "exit") == 0 || strcmp(input, "quit") == 0)
        {
            printf("Exiting.\n");
            break;
        }

        char command[MAX_INPUT + 64];
        int written = snprintf(command, sizeof(command),
                               "open -g -a \"%s\"", input);

        if (written < 0 || (size_t)written >= sizeof(command))
        {
            fprintf(stderr, "Command too long.\n");
            continue;
        }

        int ret = system(command);

        if (ret == 0)
        {
            printf("Requested preload for app: %s\n", input);
        }
        else
        {
            printf("Failed to preload app: %s\n", input);
            printf("Maybe the app name is incorrect.\n");
        }
    }

    return 0;
}