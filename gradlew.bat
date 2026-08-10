@echo off
rem Gradle startup script
if defined JAVA_HOME (
  set JAVA_EXE=%JAVA_HOME%\bin\java.exe
) else (
  set JAVA_EXE=java
)
%JAVA_EXE% -version >NUL 2>&1
if %ERRORLEVEL% neq 0 (
  echo Gradle is required. Install Gradle first.
  exit /b 1
)

if defined GRADLE_HOME (
  set GRADLE_CMD=%GRADLE_HOME%\bin\gradle.bat
) else (
  set GRADLE_CMD=gradle.bat
)
%GRADLE_CMD% %*
