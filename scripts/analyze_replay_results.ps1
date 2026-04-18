param(
    [string]$ResultsDir = ".\\dataset_cache\\exports"
)

Get-ChildItem -Path $ResultsDir -Filter "*_replay_results.csv" | ForEach-Object {
    Write-Host "Replay result file: $($_.FullName)"
    Get-Content $_.FullName | Select-Object -First 5
}
