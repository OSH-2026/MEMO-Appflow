$target = Join-Path $PSScriptRoot "..\\dataset_cache\\sample_usage.csv"
@'
packageName,timestamp,userId
com.android.settings,1700000000000,u1
com.google.android.youtube,1700000300000,u1
com.android.chrome,1700000600000,u1
com.google.android.youtube,1700000900000,u1
com.android.chrome,1700001200000,u1
'@ | Set-Content -Path $target
