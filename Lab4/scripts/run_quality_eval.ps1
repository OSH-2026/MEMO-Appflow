$ErrorActionPreference = "Stop"

$exe = ".\llama.cpp\build-rpc\bin\Release\llama-cli.exe"
$model = "models\Qwen_Qwen3.5-0.8B-Q4_K_M.gguf"
$prompts = Get-Content -Path ".\prompts_quality.txt" -Encoding UTF8 | Where-Object { $_.Trim().Length -gt 0 }

New-Item -ItemType Directory -Force -Path ".\results\quality" | Out-Null

for ($i = 0; $i -lt $prompts.Count; $i++) {
    $index = $i + 1
    $promptFile = ".\results\quality\prompt_$index.txt"
    $outputFile = ".\results\quality\output_$index.txt"
    Set-Content -Path $promptFile -Value $prompts[$i] -Encoding UTF8
    & $exe -m $model --file $promptFile -n 64 --threads 12 --batch-size 512 --no-mmap --ctx-size 2048 --single-turn --perf |
        Tee-Object -FilePath $outputFile
}
