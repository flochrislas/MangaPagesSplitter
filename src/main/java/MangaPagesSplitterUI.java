import javax.swing.*;
import javax.swing.border.*;
import java.awt.*;
import javax.swing.event.ChangeListener;
import java.awt.event.*;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.ExecutionException;

public class MangaPagesSplitterUI extends JFrame {
    // Main configuration objects
    private JTextField rootFolderField;
    private JButton browseButton;
    private JRadioButton autoDetectRadio, keepOriginalRadio, splitAllRadio;
    private JRadioButton japaneseRadio, westernRadio;
    private JRadioButton keepFilesRadio, deleteFilesRadio;
    private JCheckBox skipImagesCheckbox;
    private JSpinner skipStartSpinner, skipEndSpinner;
    private JCheckBox rotateWideImagesCheckbox;
    
    // Output format selection
    private JRadioButton cbzFormatRadio, cbrFormatRadio, zipFormatRadio, rarFormatRadio, folderFormatRadio;
    
    // UI components for feedback
    private JTextPane inputFilesPane; // Changed from previewPane
    private JTextArea logArea;
    private JProgressBar progressBar;
    private JButton startButton;
    private JButton cancelButton;
    
    private SwingWorker<Void, String> currentWorker = null;
    
    // Configuration values
    private int splitMode = 0; // 0=auto, 1=keep original, 2=split all
    private boolean isJapaneseManga = true;
    private boolean deleteOriginals = false;
    private int skipImagesFromStart = 0;
    private int skipImagesFromEnd = 0;
    private boolean rotateWideImages = false;
    private String rootFolder = "";
    private String outputFormat = "cbz"; // Default output format
    
    public MangaPagesSplitterUI() {
        setTitle("Manga Pages Splitter");
        setSize(900, 700);
        setMinimumSize(new Dimension(800, 600));
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLocationRelativeTo(null);
        
        initComponents();
        layoutComponents();
        wireEvents();
        updatePreview();
    }
    
    private void initComponents() {
        // Root folder selection
        rootFolderField = new JTextField(30);
        browseButton = new JButton("Browse...");
        
        // Splitting options
        ButtonGroup splitGroup = new ButtonGroup();
        autoDetectRadio = new JRadioButton("Only split wide images (smart)", true);
        keepOriginalRadio = new JRadioButton("No split at all");
        splitAllRadio = new JRadioButton("Split all images in half");
        splitGroup.add(autoDetectRadio);
        splitGroup.add(keepOriginalRadio);
        splitGroup.add(splitAllRadio);
        
        // Reading direction options
        ButtonGroup directionGroup = new ButtonGroup();
        japaneseRadio = new JRadioButton("Japanese style (right to left) [mangas]", true);
        westernRadio = new JRadioButton("Western style (left to right) [comics]");
        directionGroup.add(japaneseRadio);
        directionGroup.add(westernRadio);
        
        // Skip images options
        skipImagesCheckbox = new JCheckBox("Skip splitting certain images");
        skipStartSpinner = new JSpinner(new SpinnerNumberModel(0, 0, 100, 1));
        skipEndSpinner = new JSpinner(new SpinnerNumberModel(0, 0, 100, 1));
        skipStartSpinner.setEnabled(false);
        skipEndSpinner.setEnabled(false);
        
        // Rotation option
        rotateWideImagesCheckbox = new JCheckBox("Rotate wide images 90° clockwise");
        
        // File deletion options
        ButtonGroup deletionGroup = new ButtonGroup();
        keepFilesRadio = new JRadioButton("Keep input files", true);
        deleteFilesRadio = new JRadioButton("Delete input files after processing");
        deletionGroup.add(keepFilesRadio);
        deletionGroup.add(deleteFilesRadio);
        
        // Output format options
        ButtonGroup formatGroup = new ButtonGroup();
        cbzFormatRadio = new JRadioButton("CBZ (Comic Book ZIP)", true);
        cbrFormatRadio = new JRadioButton("CBR (Comic Book RAR)");
        zipFormatRadio = new JRadioButton("ZIP archive");
        rarFormatRadio = new JRadioButton("RAR archive");
        folderFormatRadio = new JRadioButton("Folder with images (no archive)");
        formatGroup.add(cbzFormatRadio);
        formatGroup.add(cbrFormatRadio);
        formatGroup.add(zipFormatRadio);
        formatGroup.add(rarFormatRadio);
        formatGroup.add(folderFormatRadio);
        
        // Preview pane renamed to input files pane
        inputFilesPane = new JTextPane();
        inputFilesPane.setEditable(false);
        inputFilesPane.setContentType("text/html");
        
        // Log area
        logArea = new JTextArea();
        logArea.setEditable(false);
        logArea.setFont(new Font("Monospaced", Font.PLAIN, 12));
        
        // Control buttons
        startButton = new JButton("Start Processing");
        cancelButton = new JButton("Cancel");
        cancelButton.setEnabled(false);
        
        // Progress bar
        progressBar = new JProgressBar();
        progressBar.setStringPainted(true);
        progressBar.setString("Ready");
    }
    
    private void layoutComponents() {
        setLayout(new BorderLayout());
        
        // North panel: root folder selection
        JPanel northPanel = new JPanel(new BorderLayout(5, 0));
        JPanel folderPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        folderPanel.add(new JLabel("Input files location:"));
        folderPanel.add(rootFolderField);
        folderPanel.add(browseButton);
        northPanel.add(folderPanel, BorderLayout.CENTER);
        northPanel.setBorder(new EmptyBorder(10, 10, 5, 10));
        add(northPanel, BorderLayout.NORTH);
        
        // West panel: configuration options
        JPanel westPanel = new JPanel();
        westPanel.setLayout(new BoxLayout(westPanel, BoxLayout.Y_AXIS));
        westPanel.setBorder(new EmptyBorder(5, 10, 5, 10));
        westPanel.setPreferredSize(new Dimension(300, 400));
        
        // Panel for splitting options
        JPanel splitPanel = createSectionPanel("Image Splitting Options", 280, 90);
        splitPanel.setLayout(new BoxLayout(splitPanel, BoxLayout.Y_AXIS));
        
        // Add components to splitting panel
        autoDetectRadio.setAlignmentX(LEFT_ALIGNMENT);
        keepOriginalRadio.setAlignmentX(LEFT_ALIGNMENT);
        splitAllRadio.setAlignmentX(LEFT_ALIGNMENT);
        
        splitPanel.add(autoDetectRadio);
        splitPanel.add(keepOriginalRadio);
        splitPanel.add(splitAllRadio);
        westPanel.add(splitPanel);
        westPanel.add(Box.createRigidArea(new Dimension(0, 8)));
        
        // Panel for reading direction
        JPanel directionPanel = createSectionPanel("Reading Direction", 280, 70);
        directionPanel.setLayout(new BoxLayout(directionPanel, BoxLayout.Y_AXIS));
        
        japaneseRadio.setAlignmentX(LEFT_ALIGNMENT);
        westernRadio.setAlignmentX(LEFT_ALIGNMENT);
        
        directionPanel.add(japaneseRadio);
        directionPanel.add(westernRadio);
        westPanel.add(directionPanel);
        westPanel.add(Box.createRigidArea(new Dimension(0, 8)));
        
        // Panel for page exceptions
        JPanel exceptionsPanel = createSectionPanel("Auto-split Exceptions", 280, 100);
        exceptionsPanel.setLayout(new BoxLayout(exceptionsPanel, BoxLayout.Y_AXIS));
        
        skipImagesCheckbox.setAlignmentX(LEFT_ALIGNMENT);
        exceptionsPanel.add(skipImagesCheckbox);
        
        // Create an inner panel for spinners with fixed dimensions
        JPanel skipStartPanel = createFixedHeightPanel(30);
        skipStartPanel.add(new JLabel("Skip from start:"));
        skipStartPanel.add(skipStartSpinner);
        exceptionsPanel.add(skipStartPanel);
        
        JPanel skipEndPanel = createFixedHeightPanel(30);
        skipEndPanel.add(new JLabel("Skip from end:"));
        skipEndPanel.add(skipEndSpinner);
        exceptionsPanel.add(skipEndPanel);
        
        westPanel.add(exceptionsPanel);
        westPanel.add(Box.createRigidArea(new Dimension(0, 8)));
        
        // Panel for rotation
        JPanel rotationPanel = createSectionPanel("Image Rotation", 280, 50);
        rotationPanel.setLayout(new BoxLayout(rotationPanel, BoxLayout.Y_AXIS));
        
        rotateWideImagesCheckbox.setAlignmentX(LEFT_ALIGNMENT);
        rotationPanel.add(rotateWideImagesCheckbox);
        westPanel.add(rotationPanel);
        westPanel.add(Box.createRigidArea(new Dimension(0, 8)));
        
        // Panel for input file deletion
        JPanel deletionPanel = createSectionPanel("Input Files Handling", 280, 70);
        deletionPanel.setLayout(new BoxLayout(deletionPanel, BoxLayout.Y_AXIS));
        
        keepFilesRadio.setAlignmentX(LEFT_ALIGNMENT);
        deleteFilesRadio.setAlignmentX(LEFT_ALIGNMENT);
        deletionPanel.add(keepFilesRadio);
        deletionPanel.add(deleteFilesRadio);
        westPanel.add(deletionPanel);
        westPanel.add(Box.createRigidArea(new Dimension(0, 8)));
        
        // Panel for output format - moved to be the last panel
        JPanel outputFormatPanel = createSectionPanel("Output Format", 280, 139);
        outputFormatPanel.setLayout(new BoxLayout(outputFormatPanel, BoxLayout.Y_AXIS));
        
        cbzFormatRadio.setAlignmentX(LEFT_ALIGNMENT);
        cbrFormatRadio.setAlignmentX(LEFT_ALIGNMENT);
        zipFormatRadio.setAlignmentX(LEFT_ALIGNMENT);
        rarFormatRadio.setAlignmentX(LEFT_ALIGNMENT);
        folderFormatRadio.setAlignmentX(LEFT_ALIGNMENT);
        
        outputFormatPanel.add(cbzFormatRadio);
        outputFormatPanel.add(cbrFormatRadio);
        outputFormatPanel.add(zipFormatRadio);
        outputFormatPanel.add(rarFormatRadio);
        outputFormatPanel.add(folderFormatRadio);
        westPanel.add(outputFormatPanel);
        
        add(westPanel, BorderLayout.WEST);
        
        // Center panel: input files and log
        JPanel centerPanel = new JPanel(new BorderLayout());
        
        // Input files section (renamed from Preview)
        JPanel inputFilesPanel = createSectionPanel("Input Files", 400, 200);
        inputFilesPanel.setLayout(new BorderLayout());
        inputFilesPanel.add(new JScrollPane(inputFilesPane), BorderLayout.CENTER);
        inputFilesPanel.setPreferredSize(new Dimension(400, 200));
        
        // Process section (renamed from Processing Log)
        JPanel logPanel = createSectionPanel("Process", 400, 300);
        logPanel.setLayout(new BorderLayout());
        JScrollPane logScrollPane = new JScrollPane(logArea);
        logPanel.add(logScrollPane, BorderLayout.CENTER);
        
        JSplitPane splitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT, inputFilesPanel, logPanel);
        splitPane.setResizeWeight(0.3);
        centerPanel.add(splitPane, BorderLayout.CENTER);
        
        add(centerPanel, BorderLayout.CENTER);
        
        // South panel: control buttons and progress bar
        JPanel southPanel = new JPanel(new BorderLayout());
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttonPanel.add(cancelButton);
        buttonPanel.add(startButton);
        southPanel.add(buttonPanel, BorderLayout.EAST);
        southPanel.add(progressBar, BorderLayout.CENTER);
        southPanel.setBorder(new EmptyBorder(5, 10, 10, 10));
        add(southPanel, BorderLayout.SOUTH);
    }
    
    /**
     * Creates a panel with a titled border and fixed dimensions.
     * 
     * @param title The title for the border
     * @param width The fixed width for the panel
     * @param height The fixed height for the panel
     * @return A new JPanel with the specified properties
     */
    private JPanel createSectionPanel(String title, int width, int height) {
        JPanel panel = new JPanel();
        panel.setBorder(BorderFactory.createTitledBorder(
            BorderFactory.createEtchedBorder(), title));
        panel.setAlignmentX(LEFT_ALIGNMENT);
        
        // Set fixed dimensions
        panel.setPreferredSize(new Dimension(width, height));
        panel.setMaximumSize(new Dimension(width, height));
        panel.setMinimumSize(new Dimension(width, height));
            
        return panel;
    }
    
    /**
     * Creates a simple left-aligned panel with fixed height for UI components.
     * 
     * @param height The fixed height for the panel
     * @return A new JPanel with the specified properties
     */
    private JPanel createFixedHeightPanel(int height) {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        panel.setAlignmentX(LEFT_ALIGNMENT);
        panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, height));
        return panel;
    }
    
    private void wireEvents() {
        // Folder selection - update to refresh input files panel
        browseButton.addActionListener(e -> {
            JFileChooser chooser = new JFileChooser();
            chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            chooser.setDialogTitle("Select Root Directory for Manga Processing");
            
            if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                rootFolder = chooser.getSelectedFile().getAbsolutePath();
                rootFolderField.setText(rootFolder);
                updateInputFilesPane(); // Show files in folder
                updatePreview();        // Update preview text in log area
            }
        });
        
        // Splitting option changes
        ActionListener splitListener = e -> {
            if (autoDetectRadio.isSelected()) {
                splitMode = 0;
                setDirectionPanelEnabled(true);
                setExceptionsPanelEnabled(true);
                setRotationPanelEnabled(true);
            } else if (keepOriginalRadio.isSelected()) {
                splitMode = 1;
                setDirectionPanelEnabled(false);
                setExceptionsPanelEnabled(false);
                setRotationPanelEnabled(true);
            } else if (splitAllRadio.isSelected()) {
                splitMode = 2;
                setDirectionPanelEnabled(true);
                setExceptionsPanelEnabled(true);
                // Only enable rotation if we have exceptions
                setRotationPanelEnabled(skipImagesCheckbox.isSelected());
            }
            updatePreview();
        };
        
        autoDetectRadio.addActionListener(splitListener);
        keepOriginalRadio.addActionListener(splitListener);
        splitAllRadio.addActionListener(splitListener);
        
        // Reading direction changes
        japaneseRadio.addActionListener(e -> {
            isJapaneseManga = true;
            updatePreview();
        });
        
        westernRadio.addActionListener(e -> {
            isJapaneseManga = false;
            updatePreview();
        });
        
        // Skip images checkbox
        skipImagesCheckbox.addActionListener(e -> {
            boolean enabled = skipImagesCheckbox.isSelected();
            updateSkipComponentsEnabled(enabled);
            
            // If in "split all" mode, enable/disable rotation based on skip checkbox
            if (splitAllRadio.isSelected()) {
                setRotationPanelEnabled(enabled);
            }
            
            updatePreview();
        });
        
        // Skip spinner changes
        ChangeListener skipListener = e -> {
            if (skipImagesCheckbox.isSelected()) {
                skipImagesFromStart = (Integer) skipStartSpinner.getValue();
                skipImagesFromEnd = (Integer) skipEndSpinner.getValue();
                updatePreview();
            }
        };
        
        skipStartSpinner.addChangeListener(skipListener);
        skipEndSpinner.addChangeListener(skipListener);
        
        // Rotation checkbox
        rotateWideImagesCheckbox.addActionListener(e -> {
            rotateWideImages = rotateWideImagesCheckbox.isSelected();
            updatePreview();
        });
        
        // File deletion options
        keepFilesRadio.addActionListener(e -> {
            deleteOriginals = false;
            updatePreview();
        });
        
        deleteFilesRadio.addActionListener(e -> {
            deleteOriginals = true;
            updatePreview();
        });
        
        // Output format selection
        ActionListener formatListener = e -> {
            if (cbzFormatRadio.isSelected()) {
                outputFormat = "cbz";
            } else if (cbrFormatRadio.isSelected()) {
                outputFormat = "cbr";
            } else if (zipFormatRadio.isSelected()) {
                outputFormat = "zip";
            } else if (rarFormatRadio.isSelected()) {
                outputFormat = "rar";
            } else if (folderFormatRadio.isSelected()) {
                outputFormat = "folder";
            }
            updatePreview();
        };
        
        cbzFormatRadio.addActionListener(formatListener);
        cbrFormatRadio.addActionListener(formatListener);
        zipFormatRadio.addActionListener(formatListener);
        rarFormatRadio.addActionListener(formatListener);
        folderFormatRadio.addActionListener(formatListener);
        
        // Start button - update to clear preview text and show only logs
        startButton.addActionListener(e -> {
            if (rootFolder.isEmpty()) {
                JOptionPane.showMessageDialog(this,
                    "Please select a root folder first!",
                    "Missing Information",
                    JOptionPane.WARNING_MESSAGE);
                return;
            }
            
            clearLog(); // Clear log area including preview text
            startProcessing();
        });
        
        // Cancel button
        cancelButton.addActionListener(e -> {
            if (currentWorker != null && !currentWorker.isDone()) {
                currentWorker.cancel(true);
                appendToLog("Processing cancelled by user.");
                resetUIAfterProcessing();
            }
        });
    }
    
    /**
     * Updates the enabled state of skip spinners and their labels
     * 
     * @param enabled Whether the components should be enabled
     */
    private void updateSkipComponentsEnabled(boolean enabled) {
        skipStartSpinner.setEnabled(enabled);
        skipEndSpinner.setEnabled(enabled);
        
        // Update the labels in the spinner panels
        Container parent = skipImagesCheckbox.getParent();
        if (parent instanceof JPanel) {
            // Find the spinner panels and update their labels
            for (Component comp : parent.getComponents()) {
                if (comp instanceof JPanel) {
                    // This is one of our spinner panels
                    JPanel spinnerPanel = (JPanel) comp;
                    for (Component spinnerComponent : spinnerPanel.getComponents()) {
                        if (spinnerComponent instanceof JLabel) {
                            spinnerComponent.setEnabled(enabled);
                        }
                    }
                }
            }
        }
    }

    /**
     * Updates the input files pane with the content of the root folder
     * showing archives and folders that will be processed in green
     */
    private void updateInputFilesPane() {
        if (rootFolder.isEmpty()) {
            inputFilesPane.setText("<html><body style='font-family: Arial; font-size: 12pt; text-align: left;'>" +
                                  "<i>No folder selected</i></body></html>");
            return;
        }
        
        StringBuilder html = new StringBuilder();
        html.append("<html><body style='font-family: Arial; font-size: 12pt; text-align: left;'>");
        
        try {
            File folder = new File(rootFolder);
            File[] files = folder.listFiles();
            
            if (files == null || files.length == 0) {
                html.append("<div style='text-align: left;'><i>Folder is empty</i></div>");
            } else {
                // Sort files - directories first, then archives, then other files
                java.util.Arrays.sort(files, (f1, f2) -> {
                    boolean isDir1 = f1.isDirectory();
                    boolean isDir2 = f2.isDirectory();
                    boolean isArchive1 = isArchiveFile(f1.getPath());
                    boolean isArchive2 = isArchiveFile(f2.getPath());
                    
                    if (isDir1 && !isDir2) return -1;
                    if (!isDir1 && isDir2) return 1;
                    if (isArchive1 && !isArchive2) return -1;
                    if (!isArchive1 && isArchive2) return 1;
                    return f1.getName().compareToIgnoreCase(f2.getName());
                });
                
                html.append("<div style='text-align: left;'>");
                
                for (File file : files) {
                    String fileName = file.getName();
                    boolean isArchive = isArchiveFile(file.getPath());
                    boolean isDirectory = file.isDirectory();
                    
                    html.append("<div style='margin: 3px 0;'>");
                    
                    // Files to process are shown in green
                    if (isArchive || isDirectory) {
                        html.append("<span style='color: green;'>");
                        
                        if (isArchive) {
                            html.append("📦 "); // Archive icon
                        } else {
                            html.append("📁 "); // Folder icon
                        }
                        
                        html.append("<b>").append(fileName).append("</b>");
                        
                        if (isArchive) {
                            html.append(" (Archive)");
                        }
                        html.append("</span>");
                    } else {
                        // Files not to process are shown in gray
                        html.append("<span style='color: gray;'>");
                        html.append("📄 ").append(fileName);
                        html.append("</span>");
                    }
                    
                    html.append("</div>");
                }
                
                html.append("</div>");
            }
        } catch (Exception e) {
            html.append("<div style='text-align: left; color: red;'>Error reading folder: ").append(e.getMessage()).append("</div>");
        }
        
        html.append("</body></html>");
        inputFilesPane.setText(html.toString());
    }

    /**
     * Checks if a file is an archive (zip, cbz, rar, cbr)
     */
    private boolean isArchiveFile(String filePath) {
        String lowerPath = filePath.toLowerCase();
        return lowerPath.endsWith(".zip") || lowerPath.endsWith(".cbz") || 
               lowerPath.endsWith(".rar") || lowerPath.endsWith(".cbr");
    }

    private void updatePreview() {
        StringBuilder text = new StringBuilder();
        text.append("=== PROCESSING PREVIEW ===\n\n");
        
        // Root folder info
        if (rootFolder.isEmpty()) {
            text.append("Folder: No folder selected\n");
        } else {
            text.append("Folder: " + rootFolder + "\n");
        }
        
        // Splitting mode
        text.append("Splitting: ");
        switch (splitMode) {
            case 0:
                text.append("Auto-detect double pages\n");
                break;
            case 1:
                text.append("Keep all images original\n");
                break;
            case 2:
                text.append("Split all images in half\n");
                break;
        }
        
        // Reading direction (if applicable)
        if (splitMode == 0 || splitMode == 2) {
            text.append("Reading direction: ");
            text.append(isJapaneseManga ? 
                "Japanese style (right to left)" : 
                "Western style (left to right)");
            text.append("\n");
        }
        
        // Exception images
        if ((splitMode == 0 || splitMode == 2) && skipImagesCheckbox.isSelected()) {
            text.append("Page exceptions: Skip splitting the first ").append(skipImagesFromStart);
            text.append(" and the last ").append(skipImagesFromEnd);
            text.append(" images of each manga\n");
        }
        
        // Rotation info
        if (rotateWideImages && (splitMode != 2 || 
            (splitMode == 2 && skipImagesCheckbox.isSelected()))) {
            text.append("Image rotation: Wide images will be rotated 90° clockwise\n");
        }
        
        // Output format
        text.append("Output format: ");
        switch (outputFormat) {
            case "cbz":
                text.append("CBZ (Comic Book ZIP)");
                break;
            case "cbr":
                text.append("CBR (Comic Book RAR)");
                break;
            case "zip":
                text.append("ZIP archive");
                break;
            case "rar":
                text.append("RAR archive");
                break;
            case "folder":
                text.append("Folder with images (no archive)");
                break;
        }
        text.append("\n");
        
        // File handling
        text.append("Input Files handling: ");
        if (deleteOriginals) {
            text.append("Original files will be DELETED after processing");
        } else {
            text.append("Original files will be kept");
        }
        text.append("\n\n");
        
        // What will happen
        text.append("THE PROGRAM WILL:\n");
        text.append("- Extract all archive files (CBZ, CBR, ZIP, RAR)\n");
        
        if (splitMode == 0) {
            text.append("- Analyze each image and split those that are wider than tall\n");
        } else if (splitMode == 2) {
            text.append("- Split all images in half\n");
        }
        
        if ((splitMode == 0 || splitMode == 2) && skipImagesCheckbox.isSelected() && 
            (skipImagesFromStart > 0 || skipImagesFromEnd > 0)) {
            text.append("- Skip splitting images at the beginning and end as specified\n");
        }
        
        if (rotateWideImages && (splitMode != 2 || 
            (splitMode == 2 && skipImagesCheckbox.isSelected()))) {
            text.append("- Rotate wide images (width > height) 90° clockwise\n");
        }
        
        text.append("- Create new ");
        switch (outputFormat) {
            case "cbz":
                text.append("CBZ files");
                break;
            case "cbr":
                text.append("CBR files");
                break;
            case "zip":
                text.append("ZIP archives");
                break;
            case "rar":
                text.append("RAR archives");
                break;
            case "folder":
                text.append("folders with processed images");
                break;
        }
        text.append(" for each manga\n");
        
        if (deleteOriginals) {
            text.append("- Delete original archive files and extracted folders\n");
        }
        
        // Show preview in the log area now
        logArea.setText(text.toString());
    }
    
    // Add method to check if processing is cancelled
    public boolean isCancelled() {
        return currentWorker != null && currentWorker.isCancelled();
    }
    
    private void startProcessing() {
        // Get current configuration
        if (skipImagesCheckbox.isSelected()) {
            skipImagesFromStart = (Integer) skipStartSpinner.getValue();
            skipImagesFromEnd = (Integer) skipEndSpinner.getValue();
        } else {
            skipImagesFromStart = 0;
            skipImagesFromEnd = 0;
        }
        rotateWideImages = rotateWideImagesCheckbox.isSelected();
        
        // Update UI for processing state
        setProcessingState(true);
        clearLog();
        appendToLog("Starting manga processing...");
        appendToLog("Root folder: " + rootFolder);
        
        // Create and start worker thread for background processing
        currentWorker = new SwingWorker<Void, String>() {
            @Override
            protected Void doInBackground() {
                try {
                    publish("Split mode: " + getSplitModeName(splitMode));
                    if (splitMode == 0 || splitMode == 2) {
                        publish("Reading direction: " + (isJapaneseManga ? "Japanese (right to left)" : "Western (left to right)"));
                    }
                    
                    if (skipImagesCheckbox.isSelected() && (skipImagesFromStart > 0 || skipImagesFromEnd > 0)) {
                        publish("Skipping " + skipImagesFromStart + " images from start and " + 
                                skipImagesFromEnd + " images from end of each manga");
                    }
                    
                    if (rotateWideImages && splitMode != 2) {
                        publish("Wide images will be rotated 90° clockwise");
                    }
                    
                    publish("Output format: " + outputFormat);
                    publish("File handling: " + (deleteOriginals ? "Delete originals" : "Keep originals"));
                    publish("------------------------------");
                    
                    // Call MangaPagesSplitter to do the actual processing
                    // Pass the UI instance and output format
                    MangaPagesSplitter.processWithUI(
                        rootFolder, splitMode, isJapaneseManga, deleteOriginals,
                        skipImagesFromStart, skipImagesFromEnd, rotateWideImages, 
                        outputFormat, MangaPagesSplitterUI.this);
                    
                } catch (Exception e) {
                    publish("ERROR: " + e.getMessage());
                    e.printStackTrace();
                }
                return null;
            }
            
            @Override
            protected void process(java.util.List<String> chunks) {
                for (String message : chunks) {
                    appendToLog(message);
                }
            }
            
            @Override
            protected void done() {
                try {
                    get(); // Will throw any exceptions from doInBackground
                    appendToLog("------------------------------");
                    appendToLog("Processing completed successfully!");
                } catch (InterruptedException e) {
                    appendToLog("Processing was interrupted!");
                } catch (ExecutionException e) {
                    appendToLog("Error during processing: " + e.getCause().getMessage());
                    e.getCause().printStackTrace();
                } finally {
                    resetUIAfterProcessing();
                }
            }
        };
        
        currentWorker.execute();
    }
    
    public void updateProgress(String status, int percentage) {
        SwingUtilities.invokeLater(() -> {
            progressBar.setValue(percentage);
            progressBar.setString(status + " (" + percentage + "%)");
        });
    }
    
    // Update publishLogMessage to be simpler - directly publish to the worker
    public void publishLogMessage(String message) {
        if (currentWorker != null && !currentWorker.isCancelled()) {
            SwingUtilities.invokeLater(() -> appendToLog(message));
        }
    }
    
    private void appendToLog(String message) {
        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss");
        String timestamp = sdf.format(new Date());
        logArea.append("[" + timestamp + "] " + message + "\n");
        
        // Scroll to the bottom
        logArea.setCaretPosition(logArea.getDocument().getLength());
    }
    
    private void clearLog() {
        logArea.setText("");
    }
    
    /**
     * Enables or disables the entire reading direction panel including its title
     * 
     * @param enabled Whether the panel should be enabled
     */
    private void setDirectionPanelEnabled(boolean enabled) {
        // Enable/disable the radio buttons
        japaneseRadio.setEnabled(enabled);
        westernRadio.setEnabled(enabled);
        
        // Get the parent panel and update its title color
        Container parent = japaneseRadio.getParent();
        if (parent instanceof JPanel) {
            JPanel panel = (JPanel) parent;
            TitledBorder border = (TitledBorder) panel.getBorder();
            border.setTitleColor(enabled ? UIManager.getColor("Label.foreground") : UIManager.getColor("Label.disabledForeground"));
            panel.repaint(); // Force a repaint to show the color change
        }
    }

    /**
     * Enables or disables the entire exceptions panel including its title
     * and all child components
     * 
     * @param enabled Whether the panel should be enabled
     */
    private void setExceptionsPanelEnabled(boolean enabled) {
        // Enable/disable the checkbox
        skipImagesCheckbox.setEnabled(enabled);
        
        // If panel is disabled, disable all children
        if (!enabled) {
            // Get all components in the spinner panels and set their enabled state to false
            Container parent = skipImagesCheckbox.getParent();
            if (parent instanceof JPanel) {
                JPanel panel = (JPanel) parent;
                
                // Set the enabled state for all child components
                for (Component comp : panel.getComponents()) {
                    if (comp instanceof JPanel) {
                        // This is one of our spinner panels
                        JPanel spinnerPanel = (JPanel) comp;
                        for (Component spinnerComponent : spinnerPanel.getComponents()) {
                            spinnerComponent.setEnabled(false);
                        }
                    }
                }
                
                // Always set spinners enabled state directly to ensure it's correct
                skipStartSpinner.setEnabled(false);
                skipEndSpinner.setEnabled(false);
                
                // Update border color
                TitledBorder border = (TitledBorder) panel.getBorder();
                border.setTitleColor(UIManager.getColor("Label.disabledForeground"));
                panel.repaint(); // Force a repaint to show the color change
            }
        } else {
            // If panel is enabled, enable checkbox and set spinner state based on checkbox
            boolean spinnerEnabled = skipImagesCheckbox.isSelected();
            updateSkipComponentsEnabled(spinnerEnabled);
            
            // Update border color
            Container parent = skipImagesCheckbox.getParent();
            if (parent instanceof JPanel) {
                JPanel panel = (JPanel) parent;
                TitledBorder border = (TitledBorder) panel.getBorder();
                border.setTitleColor(UIManager.getColor("Label.foreground"));
                panel.repaint();
            }
        }
    }

    /**
     * Enables or disables the entire rotation panel including its title
     * 
     * @param enabled Whether the panel should be enabled
     */
    private void setRotationPanelEnabled(boolean enabled) {
        // Enable/disable the checkbox
        rotateWideImagesCheckbox.setEnabled(enabled);
        
        // Get the parent panel and update its title color
        Container parent = rotateWideImagesCheckbox.getParent();
        if (parent instanceof JPanel) {
            JPanel panel = (JPanel) parent;
            TitledBorder border = (TitledBorder) panel.getBorder();
            border.setTitleColor(enabled ? UIManager.getColor("Label.foreground") : UIManager.getColor("Label.disabledForeground"));
            panel.repaint(); // Force a repaint to show the color change
        }
    }

    /**
     * Sets the enabled state of a component and all its children
     * 
     * @param component The component to update
     * @param enabled The enabled state to set
     */
    private void setComponentAndChildrenEnabled(Component component, boolean enabled) {
        component.setEnabled(enabled);
        if (component instanceof Container) {
            for (Component child : ((Container) component).getComponents()) {
                setComponentAndChildrenEnabled(child, enabled);
            }
        }
    }

    private void setProcessingState(boolean processing) {
                
        // Update UI components
        startButton.setEnabled(!processing);
        cancelButton.setEnabled(processing);
        browseButton.setEnabled(!processing);
        
        // Disable configuration while processing
        autoDetectRadio.setEnabled(!processing);
        keepOriginalRadio.setEnabled(!processing);
        splitAllRadio.setEnabled(!processing);
        
        // Update entire panels based on current mode
        if (!processing) {
            // Only update these panels if we're not in processing state
            if (autoDetectRadio.isSelected()) {
                setDirectionPanelEnabled(true);
                setExceptionsPanelEnabled(true);
                setRotationPanelEnabled(true);
            } else if (keepOriginalRadio.isSelected()) {
                setDirectionPanelEnabled(false);
                setExceptionsPanelEnabled(false);
                setRotationPanelEnabled(true);
            } else if (splitAllRadio.isSelected()) {
                setDirectionPanelEnabled(true);
                setExceptionsPanelEnabled(true);
                // Only enable rotation if we have exceptions
                setRotationPanelEnabled(skipImagesCheckbox.isSelected());
            }
        } else {
            // If processing, disable all panels
            setDirectionPanelEnabled(false);
            setExceptionsPanelEnabled(false);
            setRotationPanelEnabled(false);
        }
        
        keepFilesRadio.setEnabled(!processing);
        deleteFilesRadio.setEnabled(!processing);
        
        // Disable format selection while processing
        cbzFormatRadio.setEnabled(!processing);
        cbrFormatRadio.setEnabled(!processing);
        zipFormatRadio.setEnabled(!processing);
        rarFormatRadio.setEnabled(!processing);
        folderFormatRadio.setEnabled(!processing);
        
        // Reset progress bar
        if (processing) {
            progressBar.setValue(0);
            progressBar.setString("Starting...");
        } else {
            progressBar.setValue(0);
            progressBar.setString("Ready");
        }

        // If we're returning from processing, update the panes
        if (!processing && !rootFolder.isEmpty()) {
            updateInputFilesPane();
            updatePreview();
        }
    }
    
    private void resetUIAfterProcessing() {
        setProcessingState(false);
    }
    
    private String getSplitModeName(int mode) {
        switch (mode) {
            case 0: return "Auto-detect";
            case 1: return "Keep original";
            case 2: return "Split all";
            default: return "Unknown";
        }
    }
    
    public static void main(String[] args) {
        try {
            // Set system look and feel
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception e) {
            e.printStackTrace();
        }
        
        SwingUtilities.invokeLater(() -> new MangaPagesSplitterUI().setVisible(true));
    }
}
