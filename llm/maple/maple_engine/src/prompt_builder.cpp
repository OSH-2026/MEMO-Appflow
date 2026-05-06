#include "prompt_builder.h"
#include <sstream>
#include <algorithm>

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
        for (const auto& evidence : ctx.system_evidence) {
            oss << "  * " << evidence << "\n";
        }
    }
    if (!ctx.scheduler_goal.empty()) {
        oss << "- Scheduler goal: " << ctx.scheduler_goal << "\n";
    }
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
        oss << "You are MAPLE's adapter for MEMO Android eBPF evidence. Based on the system evidence, predict the most likely resource-demand category for the next scheduling decision.\n";
        oss << "Look at the evidence counts carefully. The category with the highest event count in the Low-level eBPF evidence is the dominant one.\n";
        oss << "Output ONLY the exact category name from the candidate list below, followed by a percentage.\n";
        oss << "Example format (these are placeholder names, do NOT output them): ExampleCategoryA (70%), ExampleCategoryB (20%), ExampleCategoryC (10%)\n";
    } else {
        oss << "You are a mobile app usage predictor. Based on the user's context, predict the most likely app category they will use next.\n";
        oss << "Be concise. Output ONLY the predicted category name and percentage.\n";
        oss << "Example format (these are placeholder names, do NOT output them): PlaceholderA (70%), PlaceholderB (20%), PlaceholderC (10%)\n";
    }
    oss << "The percentage must be from 0% to 100%. Candidate app IDs are labels, not confidence values.\n";
    oss << "Do not include reasoning, markdown, XML tags, or <think> blocks.\n";
    oss << "\n";
    oss << "Context:\n";
    // In evidence-driven mode, still show candidate categories so the model
    // knows exactly which names to pick from (critical for small models).
    append_context(oss, ctx, /*include_app_ids=*/true, /*include_installed_apps=*/true);

    oss << "\nPrediction:\n";
    oss << "Based on the global information, the next app will be a ";
    return oss.str();
}

std::string PromptBuilder::build_next_app_prompt(const UserContext& ctx,
                                                  const AppTypeResult& stage1) const {
    std::ostringstream oss;
    const bool evidence_driven = is_evidence_driven(ctx);
    if (evidence_driven) {
        oss << "You are MAPLE's ID selector for MEMO Android eBPF evidence. Choose exactly one MAPLE candidate ID for the resource-demand category.\n";
        oss << "These IDs are bridge labels for evidence/resource classes, not raw Android package names.\n";
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
