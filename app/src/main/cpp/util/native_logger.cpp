#include "util/native_logger.h"
#include <android/log.h>

namespace memoos {
void log_info(const char* message) {
    __android_log_print(ANDROID_LOG_INFO, "MemoOSNative", "%s", message);
}
}
