# MangaPagesSplitter
Transform a manga with 2 pages per image, into a manga with only one page per image.
Except if you want to keep the double-page spread format, then it is OK, you can simply batch create CBZ files, maybe rotate the images so you can read them in landscape mode etc.

This tool allows you to process transformations on your comics or mangas in batch, so you can read them more easily on your smartphone or tablet.

### Problem:
You have a bunch of mangas on your computer, but they are not in a format that is easy to read on a smartphone or tablet. For example, they are in the form of images, with 2 pages per image, and you have to zoom in and out to read them.

For example you have a manga made of images that look like this:
<img width="1149" height="656" alt="image" src="https://github.com/user-attachments/assets/d4d8a25a-1a80-46dd-ab41-7db29e926526" />

### Solution:
Use this program, and after a few clicks, your manga(s) will be transformed into a format that is easy to read on smartphones and tablets.

You get a manga made of images that look like this:

<img width="590" height="961" alt="image" src="https://github.com/user-attachments/assets/d7ef1d06-7629-4a3d-b37b-b9d03b8b83a0" />

<img width="589" height="959" alt="image" src="https://github.com/user-attachments/assets/27d0cb73-a126-4e4c-9342-3b76e3bccb5b" />

## Application's features:
- Can split all comics or manga's images vertically in half, and order pages properly.
  - Can skip a number of images at the start and end of the comic or manga.
- Can process multiple comics or mangas at once, as long as they are in the same directory.
- Comics or mangas can be in the form of a folder containing images, RAR, ZIP, CBZ or CBR files.
- Can automatically detect when to split an image or not, and even preserve special double-page spreads if they are in an otherwise single paged manga.
- Can automatically rotate double-page spreads 90 degrees clockwise.
- Can crop images from all four sides (left, right, top, bottom) before processing.
- Choose the output format: CBZ, CBR, ZIP, RAR, or a plain folder with images.
- Choose the reading direction: Japanese (right to left) or Western (left to right).
- Choose to keep or delete the original input files after processing.
- Supported image formats: JPG, JPEG, PNG, GIF, BMP, WebP.

## Flow
1. Select the root folder containing your mangas or comics. A preview of the files is shown (archives and folders are highlighted in green).
2. Configure the processing options: image cropping, splitting mode, reading direction, page exceptions, image rotation, output format, and whether to keep or delete original files.
3. Review the summary of what will be done in the Process pane.
4. Click "Start Processing". A real-time progress bar and log show the current status.
5. When processing is complete, a summary is displayed with the number of output files created.

## Limitations
- The program cannot handle RAR5 (RAR version 5) files directly. If it encounters this format, it will try to use WinRar or 7-Zip if it finds it on your computer, otherwise it will skip processing it.
- Creating RAR or CBR output files requires WinRAR to be installed. If WinRAR is not found, the program will fall back to creating a ZIP file instead.

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
