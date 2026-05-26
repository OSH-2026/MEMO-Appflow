#include <ctype.h>
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

static const char *kRealBpftrace = "/data/local/tmp/bpftrace-arm64/bin/bpftrace";
static const char *kLibPath = "/data/local/tmp/bpftrace-arm64/lib";
static const char *kBtfPath =
    "/data/local/tmp/vmlinux-4.19.278-ftrace-syscalls.raw.btf";
static const char *kTraceableFunctions = "/data/local/tmp/traceable-functions.txt";

static int is_ident(int c)
{
  return isalnum((unsigned char)c) || c == '_';
}

static int keyword_at(const char *buf, size_t len, size_t off, const char *kw)
{
  size_t kwlen = strlen(kw);

  if (off > 0 && is_ident(buf[off - 1]))
    return 0;
  if (off + kwlen > len)
    return 0;
  if (strncmp(buf + off, kw, kwlen) != 0)
    return 0;
  if (off + kwlen < len && is_ident(buf[off + kwlen]))
    return 0;
  return 1;
}

static size_t skip_ws_comments(const char *buf, size_t len, size_t off)
{
  int again = 1;

  while (again) {
    again = 0;
    while (off < len && isspace((unsigned char)buf[off]))
      off++;

    if (off + 1 < len && buf[off] == '/' && buf[off + 1] == '/') {
      off += 2;
      while (off < len && buf[off] != '\n')
        off++;
      again = 1;
    } else if (off + 1 < len && buf[off] == '/' && buf[off + 1] == '*') {
      off += 2;
      while (off + 1 < len && !(buf[off] == '*' && buf[off + 1] == '/'))
        off++;
      if (off + 1 < len)
        off += 2;
      again = 1;
    }
  }

  return off;
}

static size_t copy_string(const char *in, char *out, size_t len, size_t off, size_t *out_len)
{
  char quote = in[off++];
  out[(*out_len)++] = quote;

  while (off < len) {
    char c = in[off++];
    out[(*out_len)++] = c;
    if (c == '\\' && off < len) {
      out[(*out_len)++] = in[off++];
      continue;
    }
    if (c == quote)
      break;
  }

  return off;
}

static size_t copy_comment(const char *in, char *out, size_t len, size_t off, size_t *out_len)
{
  if (off + 1 >= len)
    return off;

  out[(*out_len)++] = in[off++];
  out[(*out_len)++] = in[off++];

  if (in[off - 1] == '/') {
    while (off < len) {
      char c = in[off++];
      out[(*out_len)++] = c;
      if (c == '\n')
        break;
    }
  } else {
    while (off < len) {
      char c = in[off++];
      out[(*out_len)++] = c;
      if (c == '*' && off < len && in[off] == '/') {
        out[(*out_len)++] = in[off++];
        break;
      }
    }
  }

  return off;
}

static size_t skip_block(const char *buf, size_t len, size_t off)
{
  int depth = 0;

  while (off < len) {
    char c = buf[off++];

    if (c == '"' || c == '\'') {
      char quote = c;
      while (off < len) {
        c = buf[off++];
        if (c == '\\' && off < len) {
          off++;
          continue;
        }
        if (c == quote)
          break;
      }
      continue;
    }

    if (c == '/' && off < len && buf[off] == '/') {
      off++;
      while (off < len && buf[off] != '\n')
        off++;
      continue;
    }

    if (c == '/' && off < len && buf[off] == '*') {
      off++;
      while (off + 1 < len && !(buf[off] == '*' && buf[off + 1] == '/'))
        off++;
      if (off + 1 < len)
        off += 2;
      continue;
    }

    if (c == '{')
      depth++;
    else if (c == '}') {
      depth--;
      if (depth == 0)
        return off;
    }
  }

  return off;
}

static int read_file(const char *path, char **data, size_t *len)
{
  FILE *fp = fopen(path, "rb");
  long size;
  char *buf;

  if (!fp)
    return -1;
  if (fseek(fp, 0, SEEK_END) != 0) {
    fclose(fp);
    return -1;
  }
  size = ftell(fp);
  if (size < 0) {
    fclose(fp);
    return -1;
  }
  rewind(fp);

  buf = malloc((size_t)size + 1);
  if (!buf) {
    fclose(fp);
    errno = ENOMEM;
    return -1;
  }
  if (fread(buf, 1, (size_t)size, fp) != (size_t)size) {
    free(buf);
    fclose(fp);
    return -1;
  }
  fclose(fp);
  buf[size] = '\0';
  *data = buf;
  *len = (size_t)size;
  return 0;
}

static int write_temp_script(const char *data, size_t len, char **path)
{
  char tmpl[] = "/data/local/tmp/bpftrace-wrapper-XXXXXX";
  int fd = mkstemp(tmpl);
  ssize_t written;

  if (fd < 0)
    return -1;

  written = write(fd, data, len);
  if (written < 0 || (size_t)written != len) {
    close(fd);
    unlink(tmpl);
    return -1;
  }
  close(fd);

  *path = strdup(tmpl);
  if (!*path) {
    unlink(tmpl);
    errno = ENOMEM;
    return -1;
  }

  return 0;
}

static int preprocess_script(const char *path, char **new_path)
{
  char *in = NULL;
  char *out = NULL;
  size_t len = 0;
  size_t i = 0;
  size_t out_len = 0;
  int depth = 0;
  int stripped = 0;
  const char *disable = getenv("BPFTRACE_ANDROID_PREPROCESS");

  if (disable && strcmp(disable, "0") == 0)
    return 0;

  if (read_file(path, &in, &len) != 0)
    return 0;

  out = malloc(len + 256);
  if (!out) {
    free(in);
    errno = ENOMEM;
    return -1;
  }

  {
    const char *prefix =
        "/* Android wrapper: BEGIN/END stripped for 4.19 perf output. */\n";
    out_len = strlen(prefix);
    memcpy(out, prefix, out_len);
  }

  while (i < len) {
    if ((in[i] == '"' || in[i] == '\'')) {
      i = copy_string(in, out, len, i, &out_len);
      continue;
    }

    if (i + 1 < len && in[i] == '/' && (in[i + 1] == '/' || in[i + 1] == '*')) {
      i = copy_comment(in, out, len, i, &out_len);
      continue;
    }

    if (depth == 0 &&
        (keyword_at(in, len, i, "BEGIN") || keyword_at(in, len, i, "END"))) {
      const char *kw = keyword_at(in, len, i, "BEGIN") ? "BEGIN" : "END";
      size_t j = skip_ws_comments(in, len, i + strlen(kw));

      if (j < len && in[j] == '{') {
        i = skip_block(in, len, j);
        stripped++;
        if (out_len > 0 && out[out_len - 1] != '\n')
          out[out_len++] = '\n';
        continue;
      }
    }

    if (in[i] == '{')
      depth++;
    else if (in[i] == '}' && depth > 0)
      depth--;

    out[out_len++] = in[i++];
  }

  free(in);

  if (!stripped) {
    free(out);
    return 0;
  }

  if (write_temp_script(out, out_len, new_path) != 0) {
    free(out);
    return -1;
  }

  free(out);
  return 1;
}

static int option_takes_arg(const char *arg)
{
  static const char *opts[] = {
    "-B", "-f", "-o", "--output", "-e", "-I", "--include", "-p", "-c",
    "--mode", "--probe-filter", "--traceable-functions", "--debuginfo", "-d"
  };
  size_t i;

  if (strncmp(arg, "--", 2) == 0 && strchr(arg, '='))
    return 0;

  for (i = 0; i < sizeof(opts) / sizeof(opts[0]); i++) {
    if (strcmp(arg, opts[i]) == 0)
      return 1;
  }

  return 0;
}

static int find_script_arg(int argc, char **argv)
{
  int skip = 0;

  for (int i = 1; i < argc; i++) {
    if (skip) {
      skip = 0;
      continue;
    }

    if (strcmp(argv[i], "--") == 0)
      return i + 1 < argc ? i + 1 : -1;

    if (argv[i][0] == '-') {
      skip = option_takes_arg(argv[i]);
      continue;
    }

    return i;
  }

  return -1;
}

static int has_traceable_functions(int argc, char **argv)
{
  for (int i = 1; i < argc; i++) {
    if (strcmp(argv[i], "--traceable-functions") == 0 ||
        strncmp(argv[i], "--traceable-functions=", 22) == 0)
      return 1;
  }
  return 0;
}

int main(int argc, char **argv)
{
  int script_idx;
  int add_traceable;
  char *preprocessed = NULL;
  char **new_argv;
  int out_argc;

  setenv("BPFTRACE_BTF", kBtfPath, 1);
  setenv("LD_LIBRARY_PATH", kLibPath, 1);
  setenv("BPFTRACE_MAX_STRLEN", "64", 0);
  setenv("BPFTRACE_ON_STACK_LIMIT", "128", 0);

  script_idx = find_script_arg(argc, argv);
  if (script_idx >= 0) {
    int rc = preprocess_script(argv[script_idx], &preprocessed);
    if (rc < 0)
      perror("bpftrace wrapper preprocess");
  }

  add_traceable = !has_traceable_functions(argc, argv);
  new_argv = malloc(sizeof(char *) * (argc + (add_traceable ? 3 : 1)));
  if (!new_argv) {
    perror("malloc");
    return 1;
  }

  new_argv[0] = (char *)kRealBpftrace;
  for (int i = 1; i < argc; i++)
    new_argv[i] = (preprocessed && i == script_idx) ? preprocessed : argv[i];

  out_argc = argc;
  if (add_traceable) {
    new_argv[out_argc++] = "--traceable-functions";
    new_argv[out_argc++] = (char *)kTraceableFunctions;
  }
  new_argv[out_argc] = NULL;

  execv(kRealBpftrace, new_argv);
  perror("execv");
  return 1;
}
