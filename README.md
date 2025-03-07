# MangaPagesSplitter
Transform a manga with 2 pages per image, into a manga with only one page per image.

### Problem:
You have a manga you want to read on a tablet or a smartphone, but it shows double-pages spreads instead of single pages and it is difficult to read on your device.

### Solution:
Use this program, and after a couple of clicks, your manga(s) will show single pages that are easy to read on smartphones and tablets.

## Application's features:
- Basic, simple, straightforward, fast, _brutal_.
- Split all manga's images vertically in half, order the right and left parts properly, and pack them into a CBZ file.
- Can process multiple mangas at once, as long as they are in the same directory.
- Mangas can be in the form of a folder containing images, RAR, ZIP, CBZ or CBR files.
- All the original files will be deleted, and only the resulting single paged manga(s) will left in the end (make a copy if you want to keep "originals").

## Flow
- When you run the program, it will ask you to choose a directory.
- Choose the directory where you placed all the mangas you want to transform.
- A warning will be displayed, click OK if you want to process.
- A message will be displayed when the process is over, click OK to close it.

## Limitations
The program cannot handle RAR5 (RAR version 5) files directly. If it encounters this format, it will try to use WinRar or 7-Zip if it finds it on your computer, otherwise it will skip processing it.

## Implementation
This is a simple Java application.
Build and dependencies are handled by Maven.
### Dependencies
It is using Junrar (a third party library) in order to extract from RAR archives (if under version 5).

## How to use

### On Windows
#### Executable
Download the MangaPagesSplitter.exe from the release directory. Double click on it.
I have signed the executable file using SignTool from the Window's SDK, but the certificate I used is self-signed.
#### Batch file
You can use the batch file MangaPagesSplitter.bat from the release directory in order to run the program as a JAR file (you need the JAR file in the same directory) without typing any command (double click the batch file). You need Java installed on your system for this to work.
#### JAR file
You can enter the java command to run the JAR file from a console. You need Java installed on your system for this to work.

### On Linux
#### Bash script
You can use the MangaPagesSplitter.sh from the release directory in order to run the program as a JAR file (you need the JAR file in the same directory). You need Java installed on your system for this to work.
#### JAR file
You can enter the java command to run the JAR file from a console. You need Java installed on your system for this to work.

Otherwise, you can simply compile the source code yourself and run it the way you like.
