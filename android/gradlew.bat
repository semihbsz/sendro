@rem
@rem Sendro Gradle start-up script for Windows.
@rem
@rem Same deal as ./gradlew: if gradle\wrapper\gradle-wrapper.jar is absent we
@rem fall back to a `gradle` on PATH, and only fail if there is neither.
@rem
@if "%DEBUG%"=="" @echo off
setlocal

set DIRNAME=%~dp0
if "%DIRNAME%"=="" set DIRNAME=.
set APP_HOME=%DIRNAME%
set WRAPPER_JAR=%APP_HOME%gradle\wrapper\gradle-wrapper.jar

if defined JAVA_HOME (
    set JAVACMD=%JAVA_HOME%\bin\java.exe
) else (
    set JAVACMD=java.exe
)

if exist "%WRAPPER_JAR%" goto useWrapper

where gradle >NUL 2>&1
if %ERRORLEVEL%==0 (
    echo gradle-wrapper.jar is not in the tree; using the Gradle on PATH. 1>&2
    gradle -p "%APP_HOME%" %*
    goto end
)

echo ERROR: gradle\wrapper\gradle-wrapper.jar is missing and no gradle is on PATH. 1>&2
echo Open android\ in Android Studio, or run: gradle wrapper --gradle-version 8.11.1 1>&2
exit /b 1

:useWrapper
"%JAVACMD%" %JAVA_OPTS% %GRADLE_OPTS% "-Dorg.gradle.appname=gradlew" -classpath "%WRAPPER_JAR%" org.gradle.wrapper.GradleWrapperMain %*

:end
endlocal
