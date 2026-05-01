#pragma once

#include "maple_types.h"
#include <iosfwd>
#include <string>

namespace maple {

// Builds MAPLE-style prompts from UserContext based on enabled feature flags.
class PromptBuilder {
public:
    explicit PromptBuilder(uint32_t flags = FeatureFlags::DEFAULT);

    // Stage 1 prompt: App Type Prediction (ATP)
    std::string build_app_type_prompt(const UserContext& ctx) const;

    // Stage 2 prompt: Next App Prediction (NTP)
    std::string build_next_app_prompt(const UserContext& ctx,
                                      const AppTypeResult& stage1) const;

    void set_flags(uint32_t flags) { flags_ = flags; }
    uint32_t flags() const { return flags_; }

private:
    uint32_t flags_;

    std::string join_categories(const std::vector<std::string>& cats) const;
    std::string join_app_ids(const std::vector<int>& ids) const;
    std::string join_locations(const std::vector<std::string>& locs) const;
    std::string build_installed_apps_text(
        const std::map<std::string, std::vector<int>>& installed) const;
    bool has_feature(uint32_t feature) const;
    bool is_evidence_driven(const UserContext& ctx) const;
    void append_context(std::ostringstream& oss,
                        const UserContext& ctx,
                        bool include_app_ids,
                        bool include_installed_apps) const;
    void append_evidence(std::ostringstream& oss, const UserContext& ctx) const;
    void append_stage1_result(std::ostringstream& oss, const AppTypeResult& stage1) const;
};

} // namespace maple
