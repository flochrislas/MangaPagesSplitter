#!/bin/bash
# MangaPagesSplitter.sh - Shell script to run the MangaPagesSplitter program

# Get the directory of this script
DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
JAR_PATH="${DIR}/MangaPagesSplitter.jar"

# Check if the JAR exists
if [ ! -f "$JAR_PATH" ]; then
    echo "ERROR: Could not find MangaPagesSplitter.jar in the current directory."
    echo "Please ensure you've built the project and the JAR file is present."
    read -p "Press Enter to continue..."
    exit 1
fi

# Run the application
echo "Starting MangaPagesSplitter..."
java -jar "$JAR_PATH" "$@"

# If we get here, the application has completed
echo "Program execution completed."
read -p "Press Enter to continue..."