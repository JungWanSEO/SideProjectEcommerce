# backend 실행 스크립트: .env 의 값을 OS 환경변수로 로드한 뒤 Spring Boot 실행.
# 사용법: PowerShell에서  ./run.ps1   (backend 폴더에서)
# 사전: docker compose up -d 로 MySQL이 떠 있어야 함.

$envFile = Join-Path $PSScriptRoot ".env"
if (-not (Test-Path $envFile)) {
    Write-Error ".env 파일이 없습니다. .env.example 을 복사해 .env 를 만드세요."
    exit 1
}

# .env 한 줄씩 읽어 KEY=VALUE 를 프로세스 환경변수로 설정 (주석/빈 줄 무시)
Get-Content $envFile | ForEach-Object {
    $line = $_.Trim()
    if ($line -and -not $line.StartsWith("#")) {
        $idx = $line.IndexOf("=")
        if ($idx -gt 0) {
            $name = $line.Substring(0, $idx).Trim()
            $value = $line.Substring($idx + 1).Trim()
            [System.Environment]::SetEnvironmentVariable($name, $value, "Process")
        }
    }
}

# dev 프로파일: 로컬 데모 데이터(카테고리·브랜드·다중항목 주문) 시드 + 추천 배치 1회 실행(DemoDataInitializer).
# 운영에선 이 프로파일을 켜지 않는다(시드 빈이 생성되지 않음).
$env:SPRING_PROFILES_ACTIVE = "dev"

Write-Output ".env 로드 완료 → Spring Boot 기동 (dev 프로파일, http://localhost:8080)"
& (Join-Path $PSScriptRoot "gradlew.bat") bootRun
