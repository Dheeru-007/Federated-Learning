$env:JAVA_HOME = "C:\Program Files\Java\jdk-21.0.10"
$env:PATH = "$env:JAVA_HOME\bin;" + $env:PATH
$env:JAVA_TOOL_OPTIONS = "-Duser.timezone=Asia/Kolkata"
Set-Location "$PSScriptRoot\backend"
mvn clean spring-boot:run