package com.memoos.maple

import org.json.JSONObject

data class Stage1Category(
    val name: String,
    val probability: Double,
)

data class MaplePrediction(
    val stage1: List<Stage1Category>,
    val predictedAppId: Int,
    val reasoning: String,
    val rawStage1: String,
    val rawStage2: String,
    val backend: String,
    val available: Boolean,
    val error: String? = null,
) {
    companion object {
        fun unavailable(reason: String): MaplePrediction {
            return MaplePrediction(
                stage1 = emptyList(),
                predictedAppId = -1,
                reasoning = "",
                rawStage1 = "",
                rawStage2 = "",
                backend = "unavailable",
                available = false,
                error = reason,
            )
        }

        fun fromJson(stage1Json: String, stage2Json: String, backend: String): MaplePrediction {
            val stage1Obj = JSONObject(stage1Json)
            val categories = mutableListOf<Stage1Category>()
            val arr = stage1Obj.optJSONArray("top_categories")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val item = arr.optJSONObject(i) ?: continue
                    categories += Stage1Category(
                        name = item.optString("name"),
                        probability = item.optDouble("prob", 0.0),
                    )
                }
            }
            val stage2Obj = JSONObject(stage2Json)
            return MaplePrediction(
                stage1 = categories,
                predictedAppId = stage2Obj.optInt("predicted_app_id", -1),
                reasoning = stage2Obj.optString("reasoning"),
                rawStage1 = stage1Obj.optString("raw_output", stage1Json),
                rawStage2 = stage2Obj.optString("raw_output", stage2Json),
                backend = backend,
                available = true,
            )
        }
    }
}
