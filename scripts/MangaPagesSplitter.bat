@echo off
REM MangaPagesSplitter.bat - Batch file to run the MangaPagesSplitter program

REM Set the proper directory - change this path if your JAR is elsewhere (default is where the batch file is located)
SET DIR=%~dp0
SET CLASSPATH=%DIR%MangaPagesSplitter.jar

REM Check if the JAR exists
IF NOT EXIST "%CLASSPATH%" (
    echo ERROR: Could not find MangaPagesSplitter.jar in the current directory.
    echo Please ensure you've built the project and the JAR file is present.
    pause
    exit /b 1
)

REM Check that Java 17 or newer is available
where java >nul 2>nul
IF ERRORLEVEL 1 (
    echo ERROR: Java is not installed or not on the PATH.
    echo MangaPagesSplitter needs Java 17 or newer ^(https://adoptium.net^),
    echo or use the portable bundle MangaPagesSplitter-windows-^<version^>.zip which needs no Java.
    pause
    exit /b 1
)
SET JAVA_MAJOR=
FOR /F "tokens=3" %%v IN ('java -version 2^>^&1 ^| findstr /i "version"') DO (
    SET JAVA_VER=%%~v
)
REM Split on "." and "-" so "17-ea" and "1.8.0_471" both yield a numeric major
FOR /F "tokens=1,2 delims=.-_+" %%a IN ("%JAVA_VER%") DO (
    IF "%%a"=="1" (SET JAVA_MAJOR=%%b) ELSE (SET JAVA_MAJOR=%%a)
)
REM Reject anything that is not a plain number
echo %JAVA_MAJOR%| findstr /r "^[0-9][0-9]*$" >nul || SET JAVA_MAJOR=0
IF %JAVA_MAJOR% LSS 17 (
    echo ERROR: Java 17 or newer is required, but the installed version is %JAVA_VER%.
    echo Install a current Java from https://adoptium.net,
    echo or use the portable bundle MangaPagesSplitter-windows-^<version^>.zip which needs no Java.
    pause
    exit /b 1
)

REM Run the application
echo Starting MangaPagesSplitter...
REM THe "%*" is to pass arguments, not necessary here, but good practice
java -jar "%CLASSPATH%" %*

REM If we get here, the application has completed
echo Program execution completed.
pause