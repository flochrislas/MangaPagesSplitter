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

REM Run the application
echo Starting MangaPagesSplitter...
REM THe "%*" is to pass arguments, not necessary here, but good practice
java -jar "%CLASSPATH%" %*

REM If we get here, the application has completed
echo Program execution completed.
pause