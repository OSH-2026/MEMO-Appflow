import argparse
import json
import os
import time
import urllib.request
from datetime import datetime


DEFAULT_SERVER = "http://127.0.0.1:8080"
DEFAULT_PROMPTS = "prompts.txt"
DEFAULT_OUTPUT = "results/ray_results.json"


def load_prompts(path):
    with open(path, "r", encoding="utf-8") as f:
        return [line.strip() for line in f if line.strip()]


def post_json(url, payload, timeout=120):
    data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(
        url,
        data=data,
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return json.loads(resp.read().decode("utf-8"))


def call_llama(server_url, prompt, n_predict):
    start = time.time()
    ok = True
    error = ""
    content = ""
    predicted_n = 0
    predicted_per_second = 0.0
    try:
        result = post_json(
            f"{server_url}/completion",
            {
                "prompt": prompt,
                "n_predict": n_predict,
                "temperature": 0.2,
                "cache_prompt": False,
            },
        )
        content = result.get("content", "")
        timings = result.get("timings", {})
        predicted_n = timings.get("predicted_n", result.get("tokens_predicted", 0))
        predicted_per_second = timings.get("predicted_per_second", 0.0)
    except Exception as exc:
        ok = False
        error = str(exc)

    end = time.time()
    return {
        "prompt": prompt,
        "start_time": datetime.fromtimestamp(start).isoformat(),
        "end_time": datetime.fromtimestamp(end).isoformat(),
        "duration_s": round(end - start, 3),
        "output_length_chars": len(content),
        "tokens_generated": predicted_n,
        "tokens_per_second": round(predicted_per_second, 3),
        "success": ok,
        "error": error,
    }


def run_sequential(prompts, server_url, n_predict):
    return [call_llama(server_url, prompt, n_predict) for prompt in prompts]


def run_ray_tasks(prompts, server_url, n_predict):
    import ray

    try:
        ray.init(address="auto", ignore_reinit_error=True, log_to_driver=False)
    except Exception:
        ray.init(ignore_reinit_error=True, log_to_driver=False)

    @ray.remote
    def infer_one(prompt):
        return call_llama(server_url, prompt, n_predict)

    refs = [infer_one.remote(prompt) for prompt in prompts]
    results = ray.get(refs)
    ray.shutdown()
    return results


def summarize(mode, wall_time_s, records):
    ok = [r for r in records if r["success"]]
    durations = [r["duration_s"] for r in ok]
    total_tokens = sum(r["tokens_generated"] for r in ok)
    return {
        "mode": mode,
        "total_requests": len(records),
        "successful_requests": len(ok),
        "wall_time_s": round(wall_time_s, 3),
        "avg_latency_s": round(sum(durations) / len(durations), 3) if durations else 0,
        "max_latency_s": round(max(durations), 3) if durations else 0,
        "total_tokens": total_tokens,
        "request_throughput_rps": round(len(ok) / wall_time_s, 3) if wall_time_s > 0 else 0,
        "token_throughput_tps": round(total_tokens / wall_time_s, 3) if wall_time_s > 0 else 0,
    }


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--server", default=DEFAULT_SERVER)
    parser.add_argument("--prompts", default=DEFAULT_PROMPTS)
    parser.add_argument("--output", default=DEFAULT_OUTPUT)
    parser.add_argument("--mode", choices=["sequential", "ray", "all"], default="all")
    parser.add_argument("--n-predict", type=int, default=32)
    args = parser.parse_args()

    prompts = load_prompts(args.prompts)
    all_results = []
    summaries = []

    if args.mode in ("sequential", "all"):
        start = time.time()
        records = run_sequential(prompts, args.server, args.n_predict)
        wall = time.time() - start
        all_results.append({"mode": "sequential", "records": records})
        summaries.append(summarize("sequential", wall, records))

    if args.mode in ("ray", "all"):
        start = time.time()
        records = run_ray_tasks(prompts, args.server, args.n_predict)
        wall = time.time() - start
        all_results.append({"mode": "ray", "records": records})
        summaries.append(summarize("ray", wall, records))

    os.makedirs(os.path.dirname(args.output), exist_ok=True)
    output = {
        "timestamp": datetime.now().isoformat(),
        "server": args.server,
        "n_predict": args.n_predict,
        "prompt_count": len(prompts),
        "summaries": summaries,
        "results": all_results,
    }
    with open(args.output, "w", encoding="utf-8") as f:
        json.dump(output, f, ensure_ascii=False, indent=2)

    for item in summaries:
        print(item)
    print(f"saved: {args.output}")


if __name__ == "__main__":
    main()
