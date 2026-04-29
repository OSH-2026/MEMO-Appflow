#pragma once

#include "maple_types.h"
#include <string>

namespace maple {

// Parses raw LLM text output into structured results.
class ResultParser {
public:
    ResultParser() = default;

    // Parse Stage 1 output: extract top categories with probabilities.
    AppTypeResult parse_app_type(const std::string& raw) const;

    // Parse Stage 2 output: extract predicted app ID.
    NextAppResult parse_next_app(const std::string& raw) const;
};

} // namespace maple
