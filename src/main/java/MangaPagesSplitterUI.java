import javax.swing.*;
import javax.swing.border.*;
import java.awt.*;
import javax.swing.event.ChangeListener;
import java.awt.event.*;
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
    
    // UI components for feedback
    private JTextPane previewPane;
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
        autoDetectRadio = new JRadioButton("Detect automatically for each manga", true);
        keepOriginalRadio = new JRadioButton("Keep original images");
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
        rotateWideImagesCheckbox = new JCheckBox("Rotate wide images (width > height) 90° clockwise");
        
        // File deletion options
        ButtonGroup deletionGroup = new ButtonGroup();
        keepFilesRadio = new JRadioButton("Keep original files", true);
        deleteFilesRadio = new JRadioButton("Delete original files after processing");
        deletionGroup.add(keepFilesRadio);
        deletionGroup.add(deleteFilesRadio);
        
        // Preview pane
        previewPane = new JTextPane();
        previewPane.setEditable(false);
        previewPane.setContentType("text/html");
        
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
        folderPanel.add(new JLabel("Root Folder:"));
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
        
        add(westPanel, BorderLayout.WEST);
        
        // Center panel: preview and log
        JPanel centerPanel = new JPanel(new BorderLayout());
        
        // Preview section
        JPanel previewPanel = createSectionPanel("Preview of Actions", 400, 200);
        previewPanel.setLayout(new BorderLayout());
        previewPanel.add(new JScrollPane(previewPane), BorderLayout.CENTER);
        previewPanel.setPreferredSize(new Dimension(400, 200));
        
        // Log section
        JPanel logPanel = createSectionPanel("Processing Log", 400, 300);
        logPanel.setLayout(new BorderLayout());
        JScrollPane logScrollPane = new JScrollPane(logArea);
        logPanel.add(logScrollPane, BorderLayout.CENTER);
        
        JSplitPane splitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT, previewPanel, logPanel);
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
        // Folder selection
        browseButton.addActionListener(e -> {
            JFileChooser chooser = new JFileChooser();
            chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            chooser.setDialogTitle("Select Root Directory for Manga Processing");
            
            if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                rootFolder = chooser.getSelectedFile().getAbsolutePath();
                rootFolderField.setText(rootFolder);
                updatePreview();
            }
        });
        
        // Splitting option changes
        ActionListener splitListener = e -> {
            if (autoDetectRadio.isSelected()) {
                splitMode = 0;
                japaneseRadio.setEnabled(true);
                westernRadio.setEnabled(true);
                skipImagesCheckbox.setEnabled(true);
                rotateWideImagesCheckbox.setEnabled(true);
            } else if (keepOriginalRadio.isSelected()) {
                splitMode = 1;
                japaneseRadio.setEnabled(false);
                westernRadio.setEnabled(false);
                skipImagesCheckbox.setEnabled(false);
                rotateWideImagesCheckbox.setEnabled(true);
            } else if (splitAllRadio.isSelected()) {
                splitMode = 2;
                japaneseRadio.setEnabled(true);
                westernRadio.setEnabled(true);
                skipImagesCheckbox.setEnabled(true);
                rotateWideImagesCheckbox.setEnabled(false);
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
            skipStartSpinner.setEnabled(enabled);
            skipEndSpinner.setEnabled(enabled);
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
        
        // Start button
        startButton.addActionListener(e -> {
            if (rootFolder.isEmpty()) {
                JOptionPane.showMessageDialog(this,
                    "Please select a root folder first!",
                    "Missing Information",
                    JOptionPane.WARNING_MESSAGE);
                return;
            }
            
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
    
    private void updatePreview() {
        StringBuilder html = new StringBuilder();
        html.append("<html><body style='font-family: Arial; font-size: 12pt'>");
        html.append("<h3>Processing Preview</h3>");
        
        // Root folder info
        if (rootFolder.isEmpty()) {
            html.append("<p><b>Folder:</b> <span style='color: red'>No folder selected</span></p>");
        } else {
            html.append("<p><b>Folder:</b> ").append(rootFolder).append("</p>");
        }
        
        // Splitting mode
        html.append("<p><b>Splitting:</b> ");
        switch (splitMode) {
            case 0:
                html.append("Auto-detect double pages</p>");
                break;
            case 1:
                html.append("Keep all images original</p>");
                break;
            case 2:
                html.append("Split all images in half</p>");
                break;
        }
        
        // Reading direction (if applicable)
        if (splitMode == 0 || splitMode == 2) {
            html.append("<p><b>Reading direction:</b> ");
            html.append(isJapaneseManga ? 
                "Japanese style (right to left)" : 
                "Western style (left to right)");
            html.append("</p>");
        }
        
        // Exception images
        if ((splitMode == 0 || splitMode == 2) && skipImagesCheckbox.isSelected()) {
            html.append("<p><b>Page exceptions:</b> Skip splitting the first ").append(skipImagesFromStart);
            html.append(" and the last ").append(skipImagesFromEnd);
            html.append(" images of each manga</p>");
        }
        
        // Rotation info
        if (rotateWideImages && splitMode != 2) {
            html.append("<p><b>Image rotation:</b> Wide images will be rotated 90° clockwise</p>");
        }
        
        // File handling
        html.append("<p><b>Input Files handling:</b> ");
        if (deleteOriginals) {
            html.append("<span style='color: #AA0000'>Original files will be deleted after processing</span>");
        } else {
            html.append("Original files will be kept");
        }
        html.append("</p>");
        
        // What will happen
        html.append("<h3>The program will:</h3>");
        html.append("<ul>");
        html.append("<li>Extract all archive files (CBZ, CBR, ZIP, RAR)</li>");
        
        if (splitMode == 0) {
            html.append("<li>Analyze each image and split those that are wider than tall</li>");
        } else if (splitMode == 2) {
            html.append("<li>Split all images in half</li>");
        }
        
        if ((splitMode == 0 || splitMode == 2) && skipImagesCheckbox.isSelected() && 
            (skipImagesFromStart > 0 || skipImagesFromEnd > 0)) {
            html.append("<li>Skip splitting images at the beginning and end as specified</li>");
        }
        
        if (rotateWideImages && splitMode != 2) {
            html.append("<li>Rotate wide images (width > height) 90° clockwise for better viewing</li>");
        }
        
        html.append("<li>Create new CBZ files for each manga</li>");
        
        if (deleteOriginals) {
            html.append("<li>Delete original archive files and extracted folders</li>");
        }
        
        html.append("</ul>");
        
        html.append("</body></html>");
        previewPane.setText(html.toString());
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
                    
                    publish("File handling: " + (deleteOriginals ? "Delete originals" : "Keep originals"));
                    publish("------------------------------");
                    
                    // Call MangaPagesSplitter to do the actual processing
                    // Pass the UI instance instead of the worker
                    MangaPagesSplitter.processWithUI(
                        rootFolder, splitMode, isJapaneseManga, deleteOriginals,
                        skipImagesFromStart, skipImagesFromEnd, rotateWideImages, 
                        MangaPagesSplitterUI.this);
                    
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
    
    private void setProcessingState(boolean processing) {
                
        // Update UI components
        startButton.setEnabled(!processing);
        cancelButton.setEnabled(processing);
        browseButton.setEnabled(!processing);
        
        // Disable configuration while processing
        autoDetectRadio.setEnabled(!processing);
        keepOriginalRadio.setEnabled(!processing);
        splitAllRadio.setEnabled(!processing);
        japaneseRadio.setEnabled(!processing && (splitMode == 0 || splitMode == 2));
        westernRadio.setEnabled(!processing && (splitMode == 0 || splitMode == 2));
        skipImagesCheckbox.setEnabled(!processing && (splitMode == 0 || splitMode == 2));
        skipStartSpinner.setEnabled(!processing && skipImagesCheckbox.isSelected());
        skipEndSpinner.setEnabled(!processing && skipImagesCheckbox.isSelected());
        rotateWideImagesCheckbox.setEnabled(!processing && splitMode != 2);
        keepFilesRadio.setEnabled(!processing);
        deleteFilesRadio.setEnabled(!processing);
        
        // Reset progress bar
        if (processing) {
            progressBar.setValue(0);
            progressBar.setString("Starting...");
        } else {
            progressBar.setValue(0);
            progressBar.setString("Ready");
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
