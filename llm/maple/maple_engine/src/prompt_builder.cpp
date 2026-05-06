#include "prompt_builder.h"
#include <sstream>
#include <algorithm>
#include <cctype>

namespace maple {

PromptBuilder::PromptBuilder(uint32_t flags) : flags_(flags) {}

std::string PromptBuilder::join_categories(const std::vector<std::string>& cats) const {
    if (cats.empty()) return "";
    std::ostringstream oss;
    for (size_t i = 0; i < cats.size(); ++i) {
        if (i > 0) {
            if (i == cats.size() - 1) oss << ", and ";
            else oss << ", ";
        }
        oss << cats[i];
    }
    return oss.str();
}

std::string PromptBuilder::join_app_ids(const std::vector<int>& ids) const {
    if (ids.empty()) return "";
    std::ostringstream oss;
    for (size_t i = 0; i < ids.size(); ++i) {
        if (i > 0) {
            if (i == ids.size() - 1) oss << ", and ";
            else oss << ", ";
        }
        oss << ids[i];
    }
    return oss.str();
}

std::string PromptBuilder::join_locations(const std::vector<std::string>& locs) const {
    if (locs.empty()) return "";
    std::ostringstream oss;
    for (size_t i = 0; i < locs.size(); ++i) {
        if (i > 0) {
            if (i == locs.size() - 1) oss << ", and ";
            else oss << ", ";
        }
        oss << locs[i];
    }
    return oss.str();
}

std::string PromptBuilder::build_installed_apps_text(
    const std::map<std::string, std::vector<int>>& installed) const {
    if (installed.empty()) return "";
    std::ostringstream oss;
    for (const auto& kv : installed) {
        oss << "- " << kv.first << ": " << join_app_ids(kv.second) << "\n";
    }
    return oss.str();
}

bool PromptBuilder::has_feature(uint32_t feature) const {
    return (flags_ & feature) != 0;
}

bool PromptBuilder::is_evidence_driven(const UserContext& ctx) const {
    return !ctx.system_evidence.empty() || !ctx.memory_pressure.empty() || !ctx.scheduler_goal.empty();
}

void PromptBuilder::append_evidence(std::ostringstream& oss, const UserContext& ctx) const {
    if (!ctx.memory_pressure.empty()) {
        oss << "- eBPF memory pressure: " << ctx.memory_pressure << ".\n";
    }
    if (!ctx.system_evidence.empty()) {
        oss << "- Low-level eBPF evidence:\n";
        size_t emitted = 0;
        for (const auto& evidence : ctx.system_evidence) {
            if (emitted >= 8) break;
            oss << "  * " << evidence << "\n";
            ++emitted;
        }
    }
    if (!ctx.scheduler_goal.empty()) {
        oss << "- Scheduler goal: " << ctx.scheduler_goal << "\n";
    }
}

static std::string evidence_count_for_category(const UserContext& ctx, const std::string& category) {
    const std::string marker = "category " + category + ": count=";
    for (const auto& evidence : ctx.system_evidence) {
        const auto pos = evidence.find(marker);
        if (pos == std::string::npos) continue;
        std::string value = evidence.substr(pos + marker.size());
        const auto end = value.find_first_not_of("0123456789");
        if (end != std::string::npos) value = value.substr(0, end);
        if (!value.empty()) return value;
    }
    return "";
}

static std::string compact_target_evidence(const UserContext& ctx) {
    for (const auto& evidence : ctx.system_evidence) {
        if (evidence.find("observed user action target app=") != std::string::npos) {
            return evidence;
        }
    }
    return "";
}

static std::string top_event_evidence(const UserContext& ctx, size_t limit) {
    std::ostringstream oss;
    size_t emitted = 0;
    for (const auto& evidence : ctx.system_evidence) {
        if (evidence.find("event_type ") == std::string::npos) continue;
        if (emitted > 0) oss << "; ";
        oss << evidence;
        if (++emitted >= limit) break;
    }
    return oss.str();
}

void PromptBuilder::append_context(std::ostringstream& oss,
                                   const UserContext& ctx,
                                   bool include_app_ids,
                                   bool include_installed_apps) const {
    if (has_feature(FeatureFlags::HISTORICAL_APP_CATEGORY) && !ctx.historical_app_categories.empty()) {
        oss << "- Recent evidence categories: " << join_categories(ctx.historical_app_categories) << ".\n";
    }
    if (include_app_ids && has_feature(FeatureFlags::HISTORICAL_APP_USAGE) && !ctx.historical_app_ids.empty()) {
        oss << "- Candidate/recent app IDs: " << join_app_ids(ctx.historical_app_ids) << ".\n";
    }
    if (include_installed_apps && has_feature(FeatureFlags::INSTALLED_APPS) && !ctx.installed_apps.empty()) {
        if (is_evidence_driven(ctx)) {
            oss << "- MAPLE candidate IDs by evidence/resource category:\n";
        } else {
            oss << "- Candidate installed app IDs by category:\n";
        }
        oss << build_installed_apps_text(ctx.installed_apps);
    }
    if (has_feature(FeatureFlags::PREDICTION_TIME) && !ctx.prediction_time.empty()) {
        oss << "- Time: " << ctx.prediction_time << ".\n";
    }
    if (has_feature(FeatureFlags::POINT_OF_INTEREST) && !ctx.points_of_interest.empty()) {
        oss << "- System/user activity hints: " << join_locations(ctx.points_of_interest) << ".\n";
    }
    if (has_feature(FeatureFlags::USER_DEMOGRAPHICS)) {
        if (!ctx.user_age.empty()) {
            oss << "- User age: " << ctx.user_age << ".\n";
        }
        if (!ctx.user_gender.empty()) {
            oss << "- User gender: " << ctx.user_gender << ".\n";
        }
    }
    append_evidence(oss, ctx);
}

void PromptBuilder::append_stage1_result(std::ostringstream& oss, const AppTypeResult& stage1) const {
    if (stage1.top_categories.empty()) {
        return;
    }
    oss << "Predicted app category: " << stage1.top_categories[0].first;
    if (stage1.top_categories[0].second > 0.0f) {
        oss << " (" << static_cast<int>(stage1.top_categories[0].second * 100) << "%)";
    }
    oss << ".\n\n";
}

std::string PromptBuilder::build_app_type_prompt(const UserContext& ctx) const {
    std::ostringstream oss;
    const bool evidence_driven = is_evidence_driven(ctx);
    if (evidence_driven) {
        oss << "Pick the next Android resource-demand category from real MEMO eBPF evidence.\n";
        oss << "Answer exactly: <Category> (<percent>%).\n";
        oss << "Use only one category name from Candidates.\n";
        oss << "Candidates: " << join_categories(ctx.historical_app_categories) << ".\n";
        oss << "Counts: ";
        bool emitted = false;
        for (const auto& category : ctx.historical_app_categories) {
            const auto count = evidence_count_for_category(ctx, category);
            if (count.empty()) continue;
            if (emitted) oss << "; ";
            oss << category << "=" << count;
            emitted = true;
        }
        if (!emitted) oss << top_event_evidence(ctx, 4);
        oss << ".\n";
        const auto target = compact_target_evidence(ctx);
        if (!target.empty()) {
            oss << "Observed action: " << target << ".\n";
        }
        if (!ctx.memory_pressure.empty()) {
            oss << "Memory: " << ctx.memory_pressure << ".\n";
        }
        oss << "Prediction:\n";
        return oss.str();
    } else {
        oss << "You are a mobile app usage predictor. Based on the user's context, predict the most likely app category they will use next.\n";
        oss << "Be concise. Output ONLY the predicted category name and percentage.\n";
        oss << "Example format (these are placeholder names, do NOT output them): PlaceholderA (70%), PlaceholderB (20%), PlaceholderC (10%)\n";
    }
    oss << "The percentage must be from 0% to 100%. Candidate app IDs are labels, not confidence values.\n";
    oss << "Do not include reasoning, markdown, XML tags, or <think> blocks.\n";
    oss << "\n";
    oss << "Context:\n";
    append_context(oss, ctx, true, true);

    oss << "\nPrediction:\n";
    oss << "Based on the global information, the next app will be a ";
    return oss.str();
}

std::string PromptBuilder::build_next_app_prompt(const UserContext& ctx,
                                                  const AppTypeResult& stage1) const {
    std::ostringstream oss;
    const bool evidence_driven = is_evidence_driven(ctx);
    if (evidence_driven) {
        const std::string predicted_category =
            stage1.top_categories.empty() ? "" : stage1.top_categories[0].first;
        std::vector<int> candidate_ids;
        oss << "Choose one MAPLE candidate ID for this eBPF category.\n";
        oss << "Return one complete valid answer. Do not print the literal string <id>.\n";
        if (!predicted_category.empty()) {
            oss << "Predicted category: " << predicted_category << ".\n";
            auto it = ctx.installed_apps.find(predicted_category);
            if (it != ctx.installed_apps.end() && !it->second.empty()) {
                candidate_ids = it->second;
                oss << "Candidate IDs: " << join_app_ids(candidate_ids) << ".\n";
            }
        }
        if (!ctx.historical_app_ids.empty()) {
            oss << "Recent IDs: " << join_app_ids(ctx.historical_app_ids) << ".\n";
        }
        if (!candidate_ids.empty()) {
            oss << "Valid answers:\n";
            for (const auto id : candidate_ids) {
                oss << "- This user will use App " << id << ".\n";
            }
        } else if (!ctx.historical_app_ids.empty()) {
            oss << "Valid answers:\n";
            for (const auto id : ctx.historical_app_ids) {
                oss << "- This user will use App " << id << ".\n";
            }
        }
        oss << "Prediction:\n";
        return oss.str();
    } else {
        oss << "You are a mobile app usage predictor. Based on the predicted app category and additional user context, choose exactly one specific app ID.\n";
    }
    oss << "You MUST output exactly one sentence in this format: This user will use App <id>.\n";
    oss << "The <id> MUST be one numeric ID from the candidate list below. Do not output a category name by itself.\n";
    oss << "Return only that sentence. Do not include reasoning, markdown, XML tags, or <think> blocks.\n";
    oss << "Example format: This user will use App 4.\n\n";

    append_stage1_result(oss, stage1);

    oss << "Additional context:\n";
    append_context(oss, ctx, true, true);

    oss << "\nPrediction:\n";
    return oss.str();
}

} // namespace maple
