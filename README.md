# MangaPagesSplitter
Transform a manga with 2 pages per image, into a manga with only one page per image. Except if you want to keep the double-page spread format, then it is OK, you can simply batch create CBZ files, maybe rotate the images so you can read them in landscape mode etc.
This tool allows you to process transformations on your comics or mangas in batch, so you can read them more easily on your smartphone or tablet.

### Problem:
You have a bunch of mangas on your computer, but they are not in a format that is easy to read on a smartphone or tablet. For example, they are in the form of images, with 2 pages per image, and you have to zoom in and out to read them.

### Solution:
Use this program, and after a few clicks, your manga(s) will be transformed into a format that is easy to read on smartphones and tablets.

## Application's features:
- Can split all comics or manga's images vertically in half, order pages properly, and pack them into a CBZ file.
  - Can skip a number of images at the start and end of the comic or manga.
- Can process multiple comics or mangas at once, as long as they are in the same directory.
- Comics or mangas can be in the form of a folder containing images, RAR, ZIP, CBZ or CBR files.
- Can automatically detect when to split an image or not, and even preserve special double-page spreads if they are in an otherwise single paged manga.
- Can automatically rotate double-page spreads 90 degrees clockwise.

## Flow
- When you run the program, it will ask you to choose your options and a directory.
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
