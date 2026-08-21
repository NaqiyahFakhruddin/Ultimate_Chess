import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import javax.sound.sampled.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.*;
import java.util.List;
import javax.imageio.ImageIO;

class SimpleChess {
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new ChessFrame().setVisible(true));
    }
}

enum PColor { WHITE, BLACK }
enum PType { KING, QUEEN, ROOK, BISHOP, KNIGHT, PAWN }

class Piece implements Serializable {
    PType type;
    PColor color;
    boolean hasMoved = false;

    Piece(PType type, PColor color) { this.type = type; this.color = color; }
    Piece(Piece other) { this.type = other.type; this.color = other.color; this.hasMoved = other.hasMoved; }

    public String glyph() {
        switch (type) {
            case KING:   return color==PColor.WHITE ? "♔" : "♚";
            case QUEEN:  return color==PColor.WHITE ? "♕" : "♛";
            case ROOK:   return color==PColor.WHITE ? "♖" : "♜";
            case BISHOP: return color==PColor.WHITE ? "♗" : "♝";
            case KNIGHT: return color==PColor.WHITE ? "♘" : "♞";
            case PAWN:   return color==PColor.WHITE ? "♙" : "♟";
        }
        return "?";
    }

    public String token() { return color + "_" + type; }
    public static Piece fromToken(String tok) {
        if(tok==null || tok.equals("null")) return null;
        String[] p = tok.split("_");
        if(p.length!=2) return null;
        PColor c = PColor.valueOf(p[0]);
        PType t = PType.valueOf(p[1]);
        return new Piece(t,c);
    }
}

class Position { int row,col; Position(int r,int c){row=r;col=c;} }

class Move {
    Position from, to;
    boolean isCapture;
    Piece promotionResult;
    Move(Position f, Position t, boolean cap){ from=f; to=t; isCapture=cap; promotionResult=null; }
    public String toString() { return from.row + "," + from.col + " -> " + to.row + "," + to.col; }
}

class SquareButton extends JButton {
    int row,col;
    SquareButton(int r,int c){ super(""); row=r; col=c; setMargin(new Insets(0,0,0,0)); }
}

class SoundPlayer {
    private static void playTone(int freq, int duration) {
        new Thread(() -> {
            try {
                AudioFormat af = new AudioFormat(44100, 8, 1, true, false);
                DataLine.Info info = new DataLine.Info(SourceDataLine.class, af);
                if(!AudioSystem.isLineSupported(info)) return;
                SourceDataLine line = (SourceDataLine) AudioSystem.getLine(info);
                line.open(af);
                line.start();

                byte[] buf = new byte[duration * 44100 / 1000];
                for(int i = 0; i < buf.length; i++) {
                    double angle = i / (44100.0 / freq) * 2.0 * Math.PI;
                    buf[i] = (byte)(Math.sin(angle) * 80);
                }
                line.write(buf, 0, buf.length);
                line.drain();
                line.close();
            } catch(Exception e) { }
        }).start();
    }

    public static void playMoveSound() {
        playTone(400, 80);
    }

    public static void playClickSound() {
        playTone(600, 40);
    }

    public static void playCaptureSound() {
        playTone(300, 100);
    }
}

class BackgroundPanel extends JPanel {
    private BufferedImage backgroundImage;
    private float opacity = 0.45f;

    public BackgroundPanel() {
        setOpaque(false);
        try {
            backgroundImage = ImageIO.read(
                    getClass().getResource("/bg_image.png")
            );
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        if(backgroundImage != null) {
            Graphics2D g2d = (Graphics2D) g.create();
            g2d.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, opacity));
            g2d.drawImage(backgroundImage, 0, 0, getWidth(), getHeight(), this);
            g2d.dispose();
        }
    }
}

class ChessFrame extends JFrame {
    private final JPanel mainCards = new JPanel(new CardLayout());
    private final BackgroundPanel menuPanel = new BackgroundPanel();
    private final JPanel gamePanel = new JPanel(new BorderLayout(8,8));

    private final JPanel boardPanel = new JPanel(new GridLayout(8,8));
    private final JPanel leftCaptured = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 4));
    private final JPanel rightCaptured = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 4));

    private final JPanel moveQualityPanel = new JPanel();
    private final JLabel moveQualityLabel = new JLabel("Move Quality", SwingConstants.CENTER);
    private final JPanel meterPanel = new JPanel();
    private double lastMoveQuality = 0.0;

    private final JLabel status = new JLabel("Welcome!");
    // Removed individual timers as requested
    private final JLabel turnTimerLabel = new JLabel("Turn: 1:30");
    private final JButton startBtn = new JButton("Start New Game");
    private final JButton exitBtn = new JButton("Exit to Menu");
    private final JButton saveBtn = new JButton("Save");
    private final JButton loadBtn = new JButton("Resume");
    private final JButton pauseBtn = new JButton("Pause");

    private final JButton whitePowerupBtn = new JButton("⚡ White Powerup");
    private final JButton blackPowerupBtn = new JButton("⚡ Black Powerup");

    private final JButton menuStartBtn = new JButton("Start New Game");
    private final JButton menuResumeBtn = new JButton("Resume From File");

    private final SquareButton[][] squares = new SquareButton[8][8];
    private Piece[][] board = new Piece[8][8];
    private PColor turn = PColor.WHITE;
    private Position selected = null;
    private boolean running = false;

    // Removed whiteTime and blackTime
    private int turnTime = 90; // Set to 90 seconds (1:30)
    private javax.swing.Timer timer;

    private String whiteName="White", blackName="Black";
    private String whiteID="W001", blackID="B001";

    private final java.util.List<Piece> whiteCaptured = new ArrayList<>();
    private final java.util.List<Piece> blackCaptured = new ArrayList<>();
    private final List<String> moveHistory = new ArrayList<>();

    private final Color lightSquare = new Color(238, 245, 216);
    private final Color darkSquare  = new Color(158, 188, 132);
    private final Color selectedSquare = new Color(250, 250, 140);
    private final Color legalTarget = new Color(200, 230, 180);
    private final Color powerupHighlight = new Color(255, 215, 120);
    private final Color buttonBg = new Color(180, 210, 150);
    private final Color menuBg = new Color(245, 245, 235);

    // New: color + timing for last move highlight & computer "think" time
    private final Color lastMoveColor = new Color(135, 206, 250);
    private int computerMoveThinkTime = 1200; // ms - how long computer "thinks" before executing
    private int lastMoveHighlightDuration = 1800; // ms - how long last move stays highlighted
    private long lastMoveTimestamp = 0L;

    private boolean playComputer = false;
    private PColor computerPlaysColor = PColor.BLACK;

    private int whiteWins_pvp = 0;
    private int blackWins_pvp = 0;
    private int draws_pvp = 0;
    private int playerWins_pvc = 0;
    private int computerWins_pvc = 0;
    private int draws_pvc = 0;
    private final File scoreFile = new File(System.getProperty("user.home"), "ultimatechess_scores.txt");

    private final Random rng = new Random();

    private Move lastMove = null;
    private Piece lastMovedPiece = null;

    private final JLabel sideWhiteName = new JLabel("White: " + whiteName);
    private final JLabel sideWhiteScore = new JLabel("Wins: 0");
    private final JLabel sideBlackName = new JLabel("Black: " + blackName);
    private final JLabel sideBlackScore = new JLabel("Wins: 0");
    private final JLabel sideDraws = new JLabel("Draws: 0");

    private boolean whitePowerupUsed = false;
    private boolean blackPowerupUsed = false;
    private boolean powerupActive = false;
    private PColor powerupActiveFor = null;

    public ChessFrame() {
        setTitle("♟ Ultimate Chess Game");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(1100, 800);
        setLocationRelativeTo(null);
        setResizable(true);

        buildMenuPanel();
        buildGamePanel();

        mainCards.add(menuPanel, "MENU");
        mainCards.add(gamePanel, "GAME");
        setContentPane(mainCards);

        showCard("MENU");
    }

    private void buildMenuPanel() {
        menuPanel.setLayout(new GridBagLayout());
        menuPanel.setBackground(menuBg);

        JPanel inner = new JPanel();
        inner.setOpaque(false);
        inner.setLayout(new BoxLayout(inner, BoxLayout.Y_AXIS));
        inner.setBorder(new EmptyBorder(50,50,50,50));

        JLabel title = new JLabel("♟ Ultimate Chess Game");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 45f));
        title.setAlignmentX(Component.CENTER_ALIGNMENT);
        title.setForeground(new Color(60, 80, 50));

        menuStartBtn.setAlignmentX(Component.CENTER_ALIGNMENT);
        menuResumeBtn.setAlignmentX(Component.CENTER_ALIGNMENT);

        styleMenuButton(menuStartBtn);
        styleMenuButton(menuResumeBtn);

        menuStartBtn.addActionListener(e -> {
            SoundPlayer.playClickSound();
            startGameFromMenu();
        });
        menuResumeBtn.addActionListener(e -> {
            SoundPlayer.playClickSound();
            JFileChooser fc = new JFileChooser();
            fc.setDialogTitle("Choose saved game TXT to resume");
            int rv = fc.showOpenDialog(this);
            if(rv!=JFileChooser.APPROVE_OPTION) return;
            File f = fc.getSelectedFile();
            readSaveFile(f, true);
            showCard("GAME");
        });

        inner.add(title);
        inner.add(Box.createRigidArea(new Dimension(0,40)));
        inner.add(menuStartBtn);
        inner.add(Box.createRigidArea(new Dimension(0,15)));
        inner.add(menuResumeBtn);

        menuPanel.add(inner);
    }

    private void styleMenuButton(JButton b) {
        b.setBackground(buttonBg);
        b.setForeground(new Color(40, 60, 30));
        b.setFont(b.getFont().deriveFont(Font.BOLD, 16f));
        b.setFocusPainted(false);
        b.setBorder(new LineBorder(darkSquare, 2, true));
        b.setPreferredSize(new Dimension(280, 50));
        b.setCursor(new Cursor(Cursor.HAND_CURSOR));

        b.addMouseListener(new MouseAdapter() {
            public void mouseEntered(MouseEvent e) {
                b.setBackground(darkSquare);
            }
            public void mouseExited(MouseEvent e) {
                b.setBackground(buttonBg);
            }
        });
    }

    private void buildGamePanel() {
        gamePanel.setBackground(menuBg);
        buildSidePanels();
        initializeSquares();
        initControls();
        setupTimer();

        clearBoard();
        renderBoard();
        loadScores();
        updateSideScoreLabels();
    }

    private void showCard(String name) {
        CardLayout cl = (CardLayout) mainCards.getLayout();
        cl.show(mainCards, name);
    }

    private void buildSidePanels() {
        JPanel leftPanel = new JPanel();
        leftPanel.setLayout(new BorderLayout(8,8));
        leftPanel.setBorder(new EmptyBorder(10,15,10,10));
        leftPanel.setPreferredSize(new Dimension(230, 0));
        leftPanel.setBackground(menuBg);

        leftCaptured.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(darkSquare, 1), whiteName + "'s Captured"));
        leftCaptured.setPreferredSize(new Dimension(210, 180));
        leftCaptured.setBackground(lightSquare);

        JPanel info = new JPanel();
        info.setLayout(new GridLayout(6,1,6,6));
        info.setBackground(menuBg);

        // Individual timers removed
        turnTimerLabel.setFont(turnTimerLabel.getFont().deriveFont(Font.BOLD, 14f));
        turnTimerLabel.setForeground(new Color(180, 60, 60));
        status.setFont(status.getFont().deriveFont(Font.PLAIN, 12f));

        info.add(turnTimerLabel);
        info.add(status);

        whitePowerupBtn.setFont(new Font("Dialog", Font.BOLD, 11));
        whitePowerupBtn.setBackground(new Color(255, 215, 100));
        whitePowerupBtn.setFocusPainted(false);
        whitePowerupBtn.setCursor(new Cursor(Cursor.HAND_CURSOR));
        whitePowerupBtn.addActionListener(e -> {
            if(!whitePowerupUsed && turn == PColor.WHITE && running) {
                SoundPlayer.playClickSound();
                activatePowerup(PColor.WHITE);
            }
        });

        blackPowerupBtn.setFont(new Font("Dialog", Font.BOLD, 11));
        blackPowerupBtn.setBackground(new Color(255, 215, 100));
        blackPowerupBtn.setFocusPainted(false);
        blackPowerupBtn.setCursor(new Cursor(Cursor.HAND_CURSOR));
        blackPowerupBtn.addActionListener(e -> {
            if(!blackPowerupUsed && turn == PColor.BLACK && running) {
                SoundPlayer.playClickSound();
                activatePowerup(PColor.BLACK);
            }
        });

        info.add(whitePowerupBtn);
        info.add(blackPowerupBtn);

        leftPanel.add(leftCaptured, BorderLayout.NORTH);
        leftPanel.add(info, BorderLayout.CENTER);

        gamePanel.add(leftPanel, BorderLayout.WEST);

        JPanel rightPanel = new JPanel(new BorderLayout(8,8));
        rightPanel.setBorder(new EmptyBorder(10,10,10,15));
        rightPanel.setPreferredSize(new Dimension(230, 0));
        rightPanel.setBackground(menuBg);

        JPanel scorePanel = new JPanel(new GridLayout(5,1,6,6));
        scorePanel.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(darkSquare, 1), "Scores"));
        scorePanel.setBackground(lightSquare);
        scorePanel.setPreferredSize(new Dimension(210, 180));

        sideWhiteName.setFont(sideWhiteName.getFont().deriveFont(Font.BOLD, 13f));
        sideBlackName.setFont(sideBlackName.getFont().deriveFont(Font.BOLD, 13f));

        scorePanel.add(sideWhiteName);
        scorePanel.add(sideWhiteScore);
        scorePanel.add(sideBlackName);
        scorePanel.add(sideBlackScore);
        scorePanel.add(sideDraws);

        rightPanel.add(scorePanel, BorderLayout.NORTH);

        initializeMoveQualityMeter();
        moveQualityPanel.setPreferredSize(new Dimension(210, 280));
        moveQualityPanel.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(darkSquare, 1), "Last Move Quality"));
        // Background set in initialize function
        rightPanel.add(moveQualityPanel, BorderLayout.CENTER);

        rightCaptured.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(darkSquare, 1), blackName + "'s Captured"));
        rightCaptured.setPreferredSize(new Dimension(210, 180));
        rightCaptured.setBackground(lightSquare);
        rightPanel.add(rightCaptured, BorderLayout.SOUTH);

        gamePanel.add(rightPanel, BorderLayout.EAST);
    }

    private void initializeSquares() {
        boardPanel.removeAll();
        boardPanel.setBorder(new LineBorder(darkSquare, 3));
        boardPanel.setPreferredSize(new Dimension(560,560));
        boardPanel.setBackground(darkSquare);

        for (int r=0;r<8;r++) {
            for (int c=0;c<8;c++) {
                SquareButton b = new SquareButton(r,c);
                b.setFont(new Font("Dialog", Font.BOLD, 40));
                b.setFocusPainted(false);
                b.setOpaque(true);
                b.setBackground(((r+c)%2==0) ? lightSquare : darkSquare);
                b.setForeground(Color.BLACK);
                b.setBorder(null);
                b.setCursor(new Cursor(Cursor.HAND_CURSOR));

                final int rr = r, cc = c;
                b.addActionListener(e -> {
                    SoundPlayer.playClickSound();
                    onSquareClicked(rr, cc);
                });

                squares[r][c] = b;
                boardPanel.add(b);
            }
        }

        JPanel centerWrap = new JPanel(new GridBagLayout());
        centerWrap.setBackground(menuBg);
        centerWrap.add(boardPanel);
        gamePanel.add(centerWrap, BorderLayout.CENTER);
    }

    private void initControls() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 10));
        panel.setBorder(new EmptyBorder(8,10,10,10));
        panel.setBackground(menuBg);

        styleControlButton(startBtn);
        styleControlButton(exitBtn);
        styleControlButton(saveBtn);
        styleControlButton(loadBtn);
        styleControlButton(pauseBtn);

        panel.add(startBtn);
        panel.add(exitBtn);
        panel.add(pauseBtn);
        panel.add(saveBtn);
        panel.add(loadBtn);

        startBtn.addActionListener(e -> {
            SoundPlayer.playClickSound();
            startGameFromMenu();
        });
        exitBtn.addActionListener(e -> {
            SoundPlayer.playClickSound();
            int choice = JOptionPane.showConfirmDialog(this,
                    "Are you sure you want to exit to menu? Current game will be lost unless saved.",
                    "Exit to Menu",
                    JOptionPane.YES_NO_OPTION);
            if(choice == JOptionPane.YES_OPTION) {
                running = false;
                showCard("MENU");
            }
        });
        saveBtn.addActionListener(e -> {
            SoundPlayer.playClickSound();
            saveGameAs();
        });
        loadBtn.addActionListener(e -> {
            SoundPlayer.playClickSound();
            importFileAndLoad();
        });
        pauseBtn.addActionListener(e -> {
            SoundPlayer.playClickSound();
            running = !running;
            pauseBtn.setText(running ? "Pause" : "Resume");
        });

        gamePanel.add(panel, BorderLayout.SOUTH);
    }

    private void styleControlButton(JButton b) {
        b.setBackground(buttonBg);
        b.setForeground(new Color(40, 60, 30));
        b.setFont(b.getFont().deriveFont(Font.BOLD, 12f));
        b.setFocusPainted(false);
        b.setBorder(new LineBorder(darkSquare, 1, true));
        b.setPreferredSize(new Dimension(120, 35));
        b.setCursor(new Cursor(Cursor.HAND_CURSOR));
    }

    private void setupTimer() {
        timer = new javax.swing.Timer(1000, e -> {
            if(!running) return;

            turnTime--;
            if(turnTime <= 0) {
                skipTurn();
                return;
            }
            // Removed individual timer logic
            updateTimers();
        });
        timer.start();
    }

    private void skipTurn() {
        String currentPlayer = (turn==PColor.WHITE) ? whiteName : blackName;
        status.setText(currentPlayer + " ran out of turn time! Turn skipped.");

        turn = oppositeColor(turn);
        turnTime = 90; // Reset to 90
        selected = null;
        powerupActive = false;
        powerupActiveFor = null;

        renderBoard();
        updateTimers();

        if(playComputer && turn == computerPlaysColor) {
            doComputerMoveIfNeeded();
        }
    }

    private void startGameFromMenu() {
        JPanel panel = new JPanel(new GridLayout(0, 1, 5, 10));
        panel.setBorder(new EmptyBorder(10, 10, 10, 10));

        JLabel modeLabel = new JLabel("Select Game Mode:");
        modeLabel.setFont(modeLabel.getFont().deriveFont(Font.BOLD, 13f));

        ButtonGroup modeGroup = new ButtonGroup();
        JRadioButton twoPlayer = new JRadioButton("Player vs Player", true);
        JRadioButton vsCompWhite = new JRadioButton("Player vs Computer (You play White)");
        JRadioButton vsCompBlack = new JRadioButton("Player vs Computer (You play Black)");

        modeGroup.add(twoPlayer);
        modeGroup.add(vsCompWhite);
        modeGroup.add(vsCompBlack);

        panel.add(modeLabel);
        panel.add(twoPlayer);
        panel.add(vsCompWhite);
        panel.add(vsCompBlack);

        int result = JOptionPane.showConfirmDialog(this, panel, "New Game Setup",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);

        if(result != JOptionPane.OK_OPTION) return;

        playComputer = !twoPlayer.isSelected();
        if(vsCompWhite.isSelected()) {
            computerPlaysColor = PColor.BLACK;
        } else if(vsCompBlack.isSelected()) {
            computerPlaysColor = PColor.WHITE;
        }

        if(playComputer) {
            if(computerPlaysColor == PColor.BLACK) {

                String w;
                while (true) {
                    w = JOptionPane.showInputDialog(this,"Enter Your Name (Playing White):", "Player");

                    if (w == null) return; // cancel

                    if (w.trim().isEmpty()) {
                        throw new IllegalArgumentException("Name cannot be empty!");
                    } else {
                        break; // valid
                    }
                }

                whiteName = w.trim();
                blackName = "Computer";

            } else {

                String b;
                while (true) {
                    b = JOptionPane.showInputDialog(this,"Enter Your Name (Playing Black):", "Player");

                    if (b == null) return;

                    if (b.trim().isEmpty()) {
                        throw new IllegalArgumentException("Name cannot be empty!");
                    } else {
                        break;
                    }
                }

                blackName = b.trim();
                whiteName = "Computer";
            }

        } else {

            String w;
            while (true) {
                w = JOptionPane.showInputDialog(this,"White Player Name:", "White");

                if (w == null) return;

                if (w.trim().isEmpty()) {
                    throw new IllegalArgumentException("Name cannot be empty!");
                } else {
                    break;
                }
            }
            whiteName = w.trim();

            String b;
            while (true) {
                b = JOptionPane.showInputDialog(this,"Black Player Name:", "Black");

                if (b == null) return;

                if (b.trim().isEmpty()) {
                    throw new IllegalArgumentException("Name cannot be empty!");
                } else {
                    break;
                }
            }
            blackName = b.trim();
        }


        setupStartingPosition();
        // Removed whiteTime/blackTime init
        turnTime = 90; // 1 min 30 sec
        turn = PColor.WHITE;
        running = true;
        selected = null;
        whiteCaptured.clear(); blackCaptured.clear();
        moveHistory.clear();
        lastMove = null; lastMovedPiece = null;
        lastMoveQuality = 0.0;
        whitePowerupUsed = false;
        blackPowerupUsed = false;
        powerupActive = false;
        powerupActiveFor = null;

        updatePowerupButtons();
        updateCapturedLabels();
        renderBoard();
        updateSideScoreLabels();
        // Reset quality meter
        updateMoveQualityDisplay();
        showCard("GAME");

        if(playComputer && computerPlaysColor==PColor.WHITE) {
            doComputerMoveIfNeeded();
        }
    }

    private void setupStartingPosition() {
        board = new Piece[8][8];
        board[0][0] = new Piece(PType.ROOK, PColor.BLACK);
        board[0][1] = new Piece(PType.KNIGHT,PColor.BLACK);
        board[0][2] = new Piece(PType.BISHOP,PColor.BLACK);
        board[0][3] = new Piece(PType.QUEEN,PColor.BLACK);
        board[0][4] = new Piece(PType.KING,PColor.BLACK);
        board[0][5] = new Piece(PType.BISHOP,PColor.BLACK);
        board[0][6] = new Piece(PType.KNIGHT,PColor.BLACK);
        board[0][7] = new Piece(PType.ROOK,PColor.BLACK);
        for(int c=0;c<8;c++) board[1][c] = new Piece(PType.PAWN,PColor.BLACK);

        board[7][0] = new Piece(PType.ROOK,PColor.WHITE);
        board[7][1] = new Piece(PType.KNIGHT,PColor.WHITE);
        board[7][2] = new Piece(PType.BISHOP,PColor.WHITE);
        board[7][3] = new Piece(PType.QUEEN,PColor.WHITE);
        board[7][4] = new Piece(PType.KING,PColor.WHITE);
        board[7][5] = new Piece(PType.BISHOP,PColor.WHITE);
        board[7][6] = new Piece(PType.KNIGHT,PColor.WHITE);
        board[7][7] = new Piece(PType.ROOK,PColor.WHITE);
        for(int c=0;c<8;c++) board[6][c] = new Piece(PType.PAWN,PColor.WHITE);
    }

    private void clearBoard(){
        board = new Piece[8][8];
    }

    private void updatePowerupButtons() {
        if(playComputer) {
            if(computerPlaysColor == PColor.WHITE) {
                whitePowerupBtn.setText("⚡ " + whiteName + " Powerup");
                blackPowerupBtn.setText("⚡ " + blackName + " Powerup");
                whitePowerupBtn.setVisible(false);
                blackPowerupBtn.setVisible(true);
            } else {
                whitePowerupBtn.setText("⚡ " + whiteName + " Powerup");
                blackPowerupBtn.setText("⚡ " + blackName + " Powerup");
                whitePowerupBtn.setVisible(true);
                blackPowerupBtn.setVisible(false);
            }
        } else {
            whitePowerupBtn.setText("⚡ " + whiteName + " Powerup");
            blackPowerupBtn.setText("⚡ " + blackName + " Powerup");
            whitePowerupBtn.setVisible(true);
            blackPowerupBtn.setVisible(true);
        }

        whitePowerupBtn.setEnabled(!whitePowerupUsed);
        blackPowerupBtn.setEnabled(!blackPowerupUsed);
    }

    private void activatePowerup(PColor color) {
        if(color == PColor.WHITE) {
            whitePowerupUsed = true;
        } else {
            blackPowerupUsed = true;
        }

        powerupActive = true;
        powerupActiveFor = color;

        updatePowerupButtons();
        renderBoard();

        JOptionPane.showMessageDialog(this,
                (color==PColor.WHITE ? whiteName : blackName) + " activated powerup! Highlighted squares show your controlled territory.",
                "Powerup Activated",
                JOptionPane.INFORMATION_MESSAGE);

        powerupActive = false;
        powerupActiveFor = null;
        renderBoard();
    }

    private void updateCapturedLabels() {
        leftCaptured.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(darkSquare, 1), whiteName + "'s Captured"));
        rightCaptured.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(darkSquare, 1), blackName + "'s Captured"));
    }

    private void initializeMoveQualityMeter() {
        moveQualityPanel.setLayout(new BorderLayout(10, 10));
        moveQualityPanel.setBackground(lightSquare);

        moveQualityLabel.setFont(new Font("Dialog", Font.BOLD, 16));
        moveQualityLabel.setBorder(new EmptyBorder(15, 10, 15, 10));
        moveQualityPanel.add(moveQualityLabel, BorderLayout.NORTH);

        meterPanel.setLayout(new BoxLayout(meterPanel, BoxLayout.Y_AXIS));
        meterPanel.setBorder(new EmptyBorder(20, 30, 20, 30));
        meterPanel.setBackground(lightSquare);
        moveQualityPanel.add(meterPanel, BorderLayout.CENTER);

        updateMoveQualityDisplay();
    }

    private void updateMoveQualityDisplay() {
        meterPanel.removeAll();

        String qualityText;
        Color meterColor;

        if(lastMoveQuality < -0.5) {
            qualityText = "WEAK MOVE";
            meterColor = new Color(220, 50, 50);
        } else if(lastMoveQuality > 0.5) {
            qualityText = "BEST MOVE";
            meterColor = new Color(50, 100, 220);
        } else {
            qualityText = "MEDIUM MOVE";
            meterColor = new Color(50, 180, 100);
        }

        JLabel qualityTextLabel = new JLabel(qualityText, SwingConstants.CENTER);
        qualityTextLabel.setFont(new Font("Dialog", Font.BOLD, 20));
        qualityTextLabel.setForeground(meterColor);
        qualityTextLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        meterPanel.add(qualityTextLabel);

        meterPanel.add(Box.createRigidArea(new Dimension(0, 20)));

        JPanel barContainer = new JPanel();
        barContainer.setLayout(new BoxLayout(barContainer, BoxLayout.Y_AXIS));
        barContainer.setBackground(lightSquare);
        barContainer.setAlignmentX(Component.CENTER_ALIGNMENT);

        JPanel colorBar = new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                Graphics2D g2 = (Graphics2D) g;
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

                g2.setColor(meterColor);
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 15, 15);

                g2.setColor(meterColor.darker());
                g2.setStroke(new BasicStroke(3));
                g2.drawRoundRect(0, 0, getWidth()-1, getHeight()-1, 15, 15);
            }
        };
        colorBar.setPreferredSize(new Dimension(180, 40));
        colorBar.setMaximumSize(new Dimension(180, 40));
        colorBar.setAlignmentX(Component.CENTER_ALIGNMENT);
        colorBar.setOpaque(false);

        barContainer.add(colorBar);
        meterPanel.add(barContainer);

        meterPanel.add(Box.createRigidArea(new Dimension(0, 20)));

        String scoreText = String.format("Evaluation: %.2f", lastMoveQuality);
        JLabel scoreLabel = new JLabel(scoreText, SwingConstants.CENTER);
        scoreLabel.setFont(new Font("Monospaced", Font.PLAIN, 14));
        scoreLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        meterPanel.add(scoreLabel);

        meterPanel.revalidate();
        meterPanel.repaint();
    }

    private void onSquareClicked(int r, int c) {
        if(!running) return;

        if(playComputer && turn == computerPlaysColor) {
            return;
        }

        Piece clicked = board[r][c];

        if(selected == null) {
            if(clicked != null && clicked.color == turn) {
                selected = new Position(r, c);
                renderBoard();
            }
        } else {
            if(clicked != null && clicked.color == turn) {
                selected = new Position(r, c);
                renderBoard();
                return;
            }

            Piece moving = board[selected.row][selected.col];
            Position to = new Position(r, c);

            if(isLegalMove(moving, selected, to)) {
                executeMove(selected, to);
            } else {
                selected = null;
                renderBoard();
            }
        }
    }

    private void executeMove(Position from, Position to) {
        Piece[][] boardBefore = copyBoard(board);
        Piece moving = board[from.row][from.col];
        Piece captured = board[to.row][to.col];

        boolean isEnPassant = (moving.type == PType.PAWN && isEnPassantCapture(moving, from, to));
        if(isEnPassant) {
            int capturedPawnRow = from.row;
            int capturedPawnCol = to.col;
            captured = board[capturedPawnRow][capturedPawnCol];
            board[capturedPawnRow][capturedPawnCol] = null;
        }

        boolean isCastle = (moving.type == PType.KING && Math.abs(to.col - from.col) == 2);
        if(isCastle) {
            int rookFromCol = (to.col > from.col) ? 7 : 0;
            int rookToCol = (to.col > from.col) ? 5 : 3;
            Piece rook = board[from.row][rookFromCol];
            board[from.row][rookFromCol] = null;
            board[from.row][rookToCol] = rook;
            if(rook != null) rook.hasMoved = true;
        }

        board[to.row][to.col] = moving;
        board[from.row][from.col] = null;
        moving.hasMoved = true;

        if(moving.type == PType.PAWN) {
            int promoRow = (moving.color == PColor.WHITE) ? 0 : 7;
            if(to.row == promoRow) {
                String[] options = {"Queen", "Rook", "Bishop", "Knight"};
                int choice = JOptionPane.showOptionDialog(this,
                        "Promote pawn to:",
                        "Pawn Promotion",
                        JOptionPane.DEFAULT_OPTION,
                        JOptionPane.QUESTION_MESSAGE,
                        null, options, options[0]);

                PType promoType = PType.QUEEN;
                if(choice == 1) promoType = PType.ROOK;
                else if(choice == 2) promoType = PType.BISHOP;
                else if(choice == 3) promoType = PType.KNIGHT;

                board[to.row][to.col] = new Piece(promoType, moving.color);
            }
        }

        if(captured != null) {
            SoundPlayer.playCaptureSound();
            if(captured.color == PColor.WHITE) {
                whiteCaptured.add(captured);
            } else {
                blackCaptured.add(captured);
            }
        } else {
            SoundPlayer.playMoveSound();
        }

        Move move = new Move(from, to, captured != null);
        lastMove = move;
        lastMovedPiece = moving;
        moveHistory.add(move.toString());

        // set timestamp so highlight appears for a while
        lastMoveTimestamp = System.currentTimeMillis();

        double quality = evaluateMoveQuality(boardBefore, board, moving.color);
        lastMoveQuality = quality;
        updateMoveQualityDisplay();

        selected = null;
        turn = oppositeColor(turn);
        turnTime = 90;

        renderBoard();
        checkForKingLoss();
        checkForGameEndByNoMoves(turn);

        if(playComputer && turn == computerPlaysColor && running) {
            javax.swing.Timer delay = new javax.swing.Timer(500, ev -> {
                doComputerMoveIfNeeded();
            });
            delay.setRepeats(false);
            delay.start();
        }
    }

    private double evaluateMoveQuality(Piece[][] boardBefore, Piece[][] boardAfter, PColor movingColor) {
        double scoreBefore = evaluateBoardScore(boardBefore);
        double scoreAfter = evaluateBoardScore(boardAfter);
        double delta = (movingColor == PColor.WHITE) ? (scoreAfter - scoreBefore) : (scoreBefore - scoreAfter);
        // Normalize range slightly for display
        return Math.max(-2.0, Math.min(2.0, delta));
    }

    private double evaluateBoardScore(Piece[][] b) {
        // Reuse evaluateMaterial for basic scoring
        return (double) evaluateMaterial(b);
    }

    private void doComputerMoveIfNeeded() {
        if(!running) return;
        if(turn != computerPlaysColor) return;

        List<Move> moves = generateAllLegalMoves(computerPlaysColor);
        if(moves.isEmpty()) return;

        Move best = null;
        int bestScore = Integer.MIN_VALUE;

        for(Move m : moves) {
            Piece[][] saved = copyBoard(board);
            Piece moving = board[m.from.row][m.from.col];
            Piece captured = board[m.to.row][m.to.col];
            board[m.to.row][m.to.col] = moving;
            board[m.from.row][m.from.col] = null;

            int score = -evaluateMaterial(board);
            if(computerPlaysColor == PColor.BLACK) score = -score;

            if(captured != null) {
                int captureVal = 0;
                switch(captured.type) {
                    case QUEEN: captureVal = 900; break;
                    case ROOK: captureVal = 500; break;
                    case BISHOP: case KNIGHT: captureVal = 300; break;
                    case PAWN: captureVal = 100; break;
                    default: break;
                }
                score += captureVal;
            }

            score += rng.nextInt(50);

            board = saved;

            if(score > bestScore) {
                bestScore = score;
                best = m;
            }
        }

        if(best != null) {
            // Show "computer thinking" and highlight source briefly, then execute after delay
            status.setText("Computer thinking...");
            // show selection so player can visually see the from-square
            selected = best.from;
            SoundPlayer.playClickSound();
            renderBoard();

            Move finalBest = best;
            javax.swing.Timer thinkTimer = new javax.swing.Timer(computerMoveThinkTime, ev -> {
                ((javax.swing.Timer)ev.getSource()).stop();
                // perform move after "thinking"
                executeMove(finalBest.from, finalBest.to);
                // update status once move complete
                status.setText((turn==PColor.WHITE? whiteName : blackName) + "'s turn");
            });
            thinkTimer.setRepeats(false);
            thinkTimer.start();
        }
    }

    private List<Move> generateAllLegalMoves(PColor color) {
        List<Move> moves = new ArrayList<>();
        for(int r=0;r<8;r++) {
            for(int c=0;c<8;c++) {
                Piece p = board[r][c];
                if(p==null || p.color!=color) continue;
                Position from = new Position(r,c);
                for(int tr=0;tr<8;tr++) {
                    for(int tc=0;tc<8;tc++) {
                        Position to = new Position(tr,tc);
                        if(isLegalMove(p, from, to)) {
                            boolean cap = board[tr][tc]!=null;
                            moves.add(new Move(from, to, cap));
                        }
                    }
                }
            }
        }
        return moves;
    }

    private boolean isLegalMove(Piece p, Position from, Position to) {
        if(p==null) return false;
        if(from.row==to.row && from.col==to.col) return false;
        Piece dest = board[to.row][to.col];
        if(dest!=null && dest.color==p.color) return false;

        int dr = to.row - from.row;
        int dc = to.col - from.col;
        boolean basicValid = false;

        switch(p.type) {
            case KING:
                if(Math.abs(dr)<=1 && Math.abs(dc)<=1) {
                    basicValid = true;
                } else if(dr==0 && Math.abs(dc)==2 && !p.hasMoved) {
                    int baseRow = (p.color==PColor.WHITE)? 7 : 0;
                    if(from.row == baseRow && from.col == 4) {
                        if(!isKingInCheck(p.color)) {
                            if(dc == 2) {
                                Piece rook = board[baseRow][7];
                                if(rook!=null && rook.type==PType.ROOK && !rook.hasMoved) {
                                    if(board[baseRow][5]==null && board[baseRow][6]==null) {
                                        if(!isSquareAttacked(oppositeColor(p.color), baseRow, 4) &&
                                                !isSquareAttacked(oppositeColor(p.color), baseRow, 5) &&
                                                !isSquareAttacked(oppositeColor(p.color), baseRow, 6)) {
                                            basicValid = true;
                                        }
                                    }
                                }
                            } else if(dc == -2) {
                                Piece rook = board[baseRow][0];
                                if(rook!=null && rook.type==PType.ROOK && !rook.hasMoved) {
                                    if(board[baseRow][1]==null && board[baseRow][2]==null && board[baseRow][3]==null) {
                                        if(!isSquareAttacked(oppositeColor(p.color), baseRow, 4) &&
                                                !isSquareAttacked(oppositeColor(p.color), baseRow, 3) &&
                                                !isSquareAttacked(oppositeColor(p.color), baseRow, 2)) {
                                            basicValid = true;
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                break;
            case QUEEN:
                if(isStraight(dr,dc) || isDiagonal(dr,dc)) {
                    basicValid = pathClear(from.row, from.col, to.row, to.col);
                }
                break;
            case ROOK:
                if(isStraight(dr,dc)) basicValid = pathClear(from.row, from.col, to.row, to.col);
                break;
            case BISHOP:
                if(isDiagonal(dr,dc)) basicValid = pathClear(from.row, from.col, to.row, to.col);
                break;
            case KNIGHT:
                basicValid = (Math.abs(dr)==2 && Math.abs(dc)==1) || (Math.abs(dr)==1 && Math.abs(dc)==2);
                break;
            case PAWN:
                int dir = (p.color==PColor.WHITE)? -1 : 1;
                if(dc==0 && board[to.row][to.col]==null) {
                    if(dr==dir) basicValid=true;
                    else if(dr==2*dir && !p.hasMoved) {
                        int mid = from.row + dir;
                        if(board[mid][from.col]==null) basicValid=true;
                    }
                } else if(Math.abs(dc)==1 && dr==dir) {
                    if(board[to.row][to.col]!=null) basicValid=true;
                    else if(isEnPassantCapture(p, from, to)) basicValid=true;
                }
                break;
        }
        if(!basicValid) return false;

        Piece[][] saved = copyBoard(board);
        board[to.row][to.col] = p;
        board[from.row][from.col] = null;
        boolean inCheck = isKingInCheck(p.color);
        board = saved;
        return !inCheck;
    }

    private boolean isStraight(int dr, int dc) { return dr==0 || dc==0; }
    private boolean isDiagonal(int dr, int dc) { return Math.abs(dr)==Math.abs(dc); }

    private boolean pathClear(int fr, int fc, int tr, int tc) {
        int rStep = Integer.signum(tr - fr);
        int cStep = Integer.signum(tc - fc);
        int r = fr + rStep;
        int c = fc + cStep;
        while(r != tr || c != tc) {
            if(board[r][c] != null) return false;
            r += rStep; c += cStep;
        }
        return true;
    }

    private PColor oppositeColor(PColor c) { return c==PColor.WHITE ? PColor.BLACK : PColor.WHITE; }

    private Piece[][] copyBoard(Piece[][] b) {
        Piece[][] copy = new Piece[8][8];
        for(int r=0;r<8;r++) for(int c=0;c<8;c++) {
            if(b[r][c]!=null) copy[r][c] = new Piece(b[r][c]);
        }
        return copy;
    }

    private int evaluateMaterial(Piece[][] b) {
        int score = 0;
        for(int r=0;r<8;r++) {
            for(int c=0;c<8;c++) {
                Piece p = b[r][c];
                if(p==null) continue;
                int val = 0;
                switch(p.type) {
                    case PAWN: val=1; break;
                    case KNIGHT: val=3; break;
                    case BISHOP: val=3; break;
                    case ROOK: val=5; break;
                    case QUEEN: val=9; break;
                    case KING: val=100; break;
                }
                score += (p.color==PColor.WHITE) ? val : -val;
            }
        }
        return score;
    }

    private void renderBoard() {
        long now = System.currentTimeMillis();
        boolean showLastMove = lastMove != null && (now - lastMoveTimestamp <= lastMoveHighlightDuration);

        for(int r=0;r<8;r++) {
            for(int c=0;c<8;c++) {
                SquareButton b = squares[r][c];
                Piece p = board[r][c];
                b.setText(p==null ? "" : p.glyph());

                // reset border each render
                b.setBorder(null);

                Color baseColor = ((r+c)%2==0) ? lightSquare : darkSquare;
                b.setBackground(baseColor);

                // highlight last move squares (if within duration)
                if(showLastMove && lastMove != null) {
                    if((lastMove.from.row==r && lastMove.from.col==c) || (lastMove.to.row==r && lastMove.to.col==c)) {
                        b.setBackground(lastMoveColor);
                        b.setBorder(new LineBorder(lastMoveColor.darker(), 3, true));
                    }
                }

                // selected square highlight (overrides last-move highlight visually)
                if(selected!=null && selected.row==r && selected.col==c) {
                    b.setBackground(selectedSquare);
                    b.setBorder(new LineBorder(selectedSquare.darker(), 3, true));
                }
            }
        }

        if(powerupActive && powerupActiveFor!=null) {
            for(int r=0;r<8;r++) {
                for(int c=0;c<8;c++) {
                    if(isSquareControlledByPlayer(powerupActiveFor, r, c)) {
                        squares[r][c].setBackground(powerupHighlight);
                    }
                }
            }
        } else if(selected!=null) {
            Piece p = board[selected.row][selected.col];
            if(p!=null && p.color==turn) {
                for(int tr=0; tr<8; tr++) {
                    for(int tc=0; tc<8; tc++) {
                        Position to = new Position(tr, tc);
                        if(isLegalMove(p, selected, to)) {
                            if(!(selected.row==tr && selected.col==tc)) {
                                squares[tr][tc].setBackground(legalTarget);
                            }
                        }
                    }
                }
            }
        }

        leftCaptured.removeAll();
        JLabel lw = new JLabel("Count: " + whiteCaptured.size());
        lw.setFont(lw.getFont().deriveFont(Font.PLAIN, 11f));
        leftCaptured.add(lw);
        for(Piece p : whiteCaptured) {
            JLabel l = new JLabel(p.glyph());
            l.setFont(new Font("Dialog", Font.BOLD, 28));
            leftCaptured.add(l);
        }

        rightCaptured.removeAll();
        JLabel lb = new JLabel("Count: " + blackCaptured.size());
        lb.setFont(lb.getFont().deriveFont(Font.PLAIN, 11f));
        rightCaptured.add(lb);
        for(Piece p : blackCaptured) {
            JLabel l = new JLabel(p.glyph());
            l.setFont(new Font("Dialog", Font.BOLD, 28));
            rightCaptured.add(l);
        }

        leftCaptured.revalidate(); leftCaptured.repaint();
        rightCaptured.revalidate(); rightCaptured.repaint();

        updateTimers();
        status.setText((turn==PColor.WHITE? whiteName : blackName) + "'s turn");
    }

    private boolean isSquareControlledByPlayer(PColor color, int row, int col) {
        for(int r=0;r<8;r++) {
            for(int c=0;c<8;c++) {
                Piece p = board[r][c];
                if(p!=null && p.color==color) {
                    Position from = new Position(r,c);
                    Position to = new Position(row, col);
                    if(isLegalMove(p, from, to)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private void updateTimers() {
        // Individual timer updates removed
        turnTimerLabel.setText("Turn: " + (turnTime/60) + ":" + String.format("%02d", turnTime%60));

        if(turnTime <= 30) {
            turnTimerLabel.setForeground(Color.RED);
        } else {
            turnTimerLabel.setForeground(new Color(180, 60, 60));
        }
    }

    private void checkForKingLoss() {
        boolean whiteKing = kingExists(PColor.WHITE);
        boolean blackKing = kingExists(PColor.BLACK);
        if(!whiteKing && !blackKing) {
            declareDraw("Both kings missing");
        } else if(!whiteKing) {
            declareWinner(PColor.BLACK, "White king captured");
        } else if(!blackKing) {
            declareWinner(PColor.WHITE, "Black king captured");
        }
    }

    private boolean kingExists(PColor color) {
        for(int r=0;r<8;r++){
            for(int c=0;c<8;c++){
                Piece p = board[r][c];
                if(p!=null && p.type==PType.KING && p.color==color) return true;
            }
        }
        return false;
    }

    private void declareWinner(PColor winner, String reason) {
        running = false;

        String who = winner==PColor.WHITE ? whiteName : blackName;

        if(playComputer) {
            if(who.equals("Computer")) {
                computerWins_pvc++;
            } else {
                playerWins_pvc++;
            }
        } else {
            if(winner==PColor.WHITE) whiteWins_pvp++;
            else blackWins_pvp++;
        }

        saveScores();
        updateSideScoreLabels();

        showGameEndDialog(who + " wins!", reason);
    }

    private void declareDraw(String reason) {
        running = false;

        if(playComputer) {
            draws_pvc++;
        } else {
            draws_pvp++;
        }

        saveScores();
        updateSideScoreLabels();

        showGameEndDialog("Draw!", reason);
    }

    private void showGameEndDialog(String title, String message) {
        Object[] options = {"Play Again", "Go to Home"};
        int choice = JOptionPane.showOptionDialog(
                this,
                message,
                title,
                JOptionPane.YES_NO_OPTION,
                JOptionPane.INFORMATION_MESSAGE,
                null,
                options,
                options[0]
        );

        if(choice == 0) {
            startGameFromMenu();
        } else {
            showCard("MENU");
        }
    }

    private void saveGameAs() {
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle("Save game (TXT)");
        int rv = fc.showSaveDialog(this);
        if(rv!=JFileChooser.APPROVE_OPTION) return;
        File f = fc.getSelectedFile();
        if(!f.getName().toLowerCase().endsWith(".txt")) f = new File(f.getAbsolutePath() + ".txt");
        writeSaveFile(f);
    }

    private void writeSaveFile(File f) {
        try(PrintWriter out = new PrintWriter(new FileWriter(f))) {
            out.println("# UltimateChess save");
            out.println("WHITE_NAME: " + whiteName);
            out.println("WHITE_ID: " + whiteID);
            // White/Black Time not saved
            out.println("BLACK_NAME: " + blackName);
            out.println("BLACK_ID: " + blackID);
            out.println("TURN_TIME: " + turnTime);
            out.println("TURN: " + turn);
            out.println("PLAY_COMPUTER: " + playComputer);
            out.println("COMPUTER_PLAYS_COLOR: " + computerPlaysColor);
            out.println("WHITE_POWERUP_USED: " + whitePowerupUsed);
            out.println("BLACK_POWERUP_USED: " + blackPowerupUsed);
            out.println("MOVE_HISTORY_COUNT: " + moveHistory.size());
            for(String m : moveHistory) out.println("MOVE: " + m);
            out.println("BOARD_START");
            for(int r=0;r<8;r++) {
                StringBuilder sb = new StringBuilder();
                for(int c=0;c<8;c++) {
                    Piece p = board[r][c];
                    sb.append(p==null ? "null" : p.token());
                    if(c<7) sb.append(' ');
                }
                out.println(sb.toString());
            }
            out.println("BOARD_END");

            out.print("WHITE_CAPTURED:");
            for(Piece p : whiteCaptured) out.print(" " + p.token());
            out.println();

            out.print("BLACK_CAPTURED:");
            for(Piece p : blackCaptured) out.print(" " + p.token());
            out.println();

            out.println("# Save complete");
            JOptionPane.showMessageDialog(this, "Saved to: " + f.getAbsolutePath());
        } catch(Exception ex) {
            JOptionPane.showMessageDialog(this, "Save failed: " + ex.getMessage());
        }
    }

    private void importFileAndLoad() {
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle("Resume saved game (TXT)");
        int rv = fc.showOpenDialog(this);
        if(rv!=JFileChooser.APPROVE_OPTION) return;
        File f = fc.getSelectedFile();
        readSaveFile(f, true);
    }

    private void readSaveFile(File f, boolean resume) {
        try(BufferedReader br = new BufferedReader(new FileReader(f))) {
            String line;
            Map<String,String> headers = new HashMap<>();
            java.util.List<String> boardLines = new ArrayList<>();
            whiteCaptured.clear(); blackCaptured.clear();
            moveHistory.clear();

            boolean inBoard = false;
            while((line = br.readLine()) != null) {
                line = line.trim();
                if(line.isEmpty() || line.startsWith("#")) continue;
                if(line.equals("BOARD_START")) { inBoard = true; continue; }
                if(line.equals("BOARD_END")) { inBoard = false; continue; }
                if(inBoard) { boardLines.add(line); continue; }

                if(line.contains(":")) {
                    int idx = line.indexOf(':');
                    String key = line.substring(0, idx).trim();
                    String value = line.substring(idx+1).trim();
                    if(key.equals("MOVE")) {
                        headers.put(key + "_" + UUID.randomUUID(), value);
                    } else if(key.equals("WHITE_CAPTURED")) {
                        if(!value.isEmpty()) {
                            String[] toks = value.split("\\s+");
                            for(String t : toks) {
                                if(t.isEmpty()) continue;
                                Piece p = Piece.fromToken(t);
                                if(p!=null) whiteCaptured.add(p);
                            }
                        }
                    } else if(key.equals("BLACK_CAPTURED")) {
                        if(!value.isEmpty()) {
                            String[] toks = value.split("\\s+");
                            for(String t : toks) {
                                if(t.isEmpty()) continue;
                                Piece p = Piece.fromToken(t);
                                if(p!=null) blackCaptured.add(p);
                            }
                        }
                    } else {
                        headers.put(key, value);
                    }
                }
            }

            if(headers.containsKey("WHITE_NAME")) whiteName = headers.get("WHITE_NAME");
            if(headers.containsKey("WHITE_ID")) whiteID = headers.get("WHITE_ID");
            // Ignored WHITE_TIME
            if(headers.containsKey("BLACK_NAME")) blackName = headers.get("BLACK_NAME");
            if(headers.containsKey("BLACK_ID")) blackID = headers.get("BLACK_ID");
            // Ignored BLACK_TIME
            if(headers.containsKey("TURN_TIME")) turnTime = Integer.parseInt(headers.get("TURN_TIME"));
            else turnTime = 120;
            if(headers.containsKey("TURN")) turn = PColor.valueOf(headers.get("TURN"));
            if(headers.containsKey("PLAY_COMPUTER")) playComputer = Boolean.parseBoolean(headers.get("PLAY_COMPUTER"));
            if(headers.containsKey("COMPUTER_PLAYS_COLOR")) computerPlaysColor = PColor.valueOf(headers.get("COMPUTER_PLAYS_COLOR"));
            if(headers.containsKey("WHITE_POWERUP_USED")) whitePowerupUsed = Boolean.parseBoolean(headers.get("WHITE_POWERUP_USED"));
            if(headers.containsKey("BLACK_POWERUP_USED")) blackPowerupUsed = Boolean.parseBoolean(headers.get("BLACK_POWERUP_USED"));

            for(String k : headers.keySet()) {
                if(k.startsWith("MOVE_")) {
                    moveHistory.add(headers.get(k));
                }
            }

            if(boardLines.size() >= 8) {
                Piece[][] nb = new Piece[8][8];
                for(int r=0;r<8;r++) {
                    String[] toks = boardLines.get(r).split("\\s+");
                    for(int c=0;c<Math.min(8, toks.length); c++) {
                        nb[r][c] = Piece.fromToken(toks[c]);
                    }
                }
                board = nb;
            } else {
                board = new Piece[8][8];
            }

            running = resume;
            selected = null;
            powerupActive = false;
            powerupActiveFor = null;
            updatePowerupButtons();
            updateCapturedLabels();
            renderBoard();
            updateSideScoreLabels();
            // Reset quality meter on load
            lastMoveQuality = 0.0;
            lastMove = null;
            lastMoveTimestamp = 0L;
            updateMoveQualityDisplay();
            JOptionPane.showMessageDialog(this, "Loaded: " + f.getAbsolutePath());
        } catch(Exception ex) {
            JOptionPane.showMessageDialog(this, "Load failed: " + ex.getMessage());
        }
    }

    private void loadScores() {
        if(!scoreFile.exists()) return;
        try(BufferedReader br = new BufferedReader(new FileReader(scoreFile))) {
            String line;
            while((line = br.readLine())!=null){
                line = line.trim();
                if(line.startsWith("#") || line.isEmpty()) continue;
                String[] parts = line.split("=");
                if(parts.length!=2) continue;
                String k = parts[0].trim();
                String v = parts[1].trim();
                if(k.equals("whiteWins_pvp")) whiteWins_pvp = Integer.parseInt(v);
                else if(k.equals("blackWins_pvp")) blackWins_pvp = Integer.parseInt(v);
                else if(k.equals("draws_pvp")) draws_pvp = Integer.parseInt(v);
                else if(k.equals("playerWins_pvc")) playerWins_pvc = Integer.parseInt(v);
                else if(k.equals("computerWins_pvc")) computerWins_pvc = Integer.parseInt(v);
                else if(k.equals("draws_pvc")) draws_pvc = Integer.parseInt(v);
            }
        } catch(Exception ex) { }
    }

    private void saveScores() {
        try(PrintWriter out = new PrintWriter(new FileWriter(scoreFile))) {
            out.println("# UltimateChess scores");
            out.println("whiteWins_pvp=" + whiteWins_pvp);
            out.println("blackWins_pvp=" + blackWins_pvp);
            out.println("draws_pvp=" + draws_pvp);
            out.println("playerWins_pvc=" + playerWins_pvc);
            out.println("computerWins_pvc=" + computerWins_pvc);
            out.println("draws_pvc=" + draws_pvc);
        } catch(Exception ex) { }
    }

    private void updateSideScoreLabels() {
        if(playComputer) {
            if(computerPlaysColor == PColor.WHITE) {
                sideWhiteName.setText("Computer");
                sideWhiteScore.setText("Wins: " + computerWins_pvc);
                sideBlackName.setText(blackName);
                sideBlackScore.setText("Wins: " + playerWins_pvc);
                sideDraws.setText("Draws: " + draws_pvc);
            } else {
                sideWhiteName.setText(whiteName);
                sideWhiteScore.setText("Wins: " + playerWins_pvc);
                sideBlackName.setText("Computer");
                sideBlackScore.setText("Wins: " + computerWins_pvc);
                sideDraws.setText("Draws: " + draws_pvc);
            }
        } else {
            sideWhiteName.setText(whiteName);
            sideWhiteScore.setText("Wins: " + whiteWins_pvp);
            sideBlackName.setText(blackName);
            sideBlackScore.setText("Wins: " + blackWins_pvp);
            sideDraws.setText("Draws: " + draws_pvp);
        }
    }

    private Position findKingPosition(Piece[][] b, PColor color) {
        for(int r=0;r<8;r++) for(int c=0;c<8;c++){
            Piece p = b[r][c];
            if(p!=null && p.type==PType.KING && p.color==color) return new Position(r,c);
        }
        return null;
    }

    private boolean isKingInCheck(PColor color) {
        Position kp = findKingPosition(board, color);
        if(kp==null) return false;
        return isSquareAttacked(oppositeColor(color), kp.row, kp.col);
    }

    private boolean isSquareAttacked(PColor byColor, int row, int col) {
        return isSquareAttackedOnBoard(board, byColor, row, col);
    }

    private boolean isSquareAttackedOnBoard(Piece[][] b, PColor byColor, int row, int col) {
        for(int r=0;r<8;r++){
            for(int c=0;c<8;c++){
                Piece p = b[r][c];
                if(p==null || p.color!=byColor) continue;
                if(canPieceAttackOnBoard(b, p, new Position(r,c), row, col)) return true;
            }
        }
        return false;
    }

    private boolean canPieceAttackOnBoard(Piece[][] b, Piece p, Position from, int tr, int tc) {
        int dr = tr - from.row;
        int dc = tc - from.col;
        switch(p.type) {
            case KING:
                return Math.abs(dr)<=1 && Math.abs(dc)<=1;
            case QUEEN:
                if((isStraight(dr,dc) || isDiagonal(dr,dc))) {
                    return pathClearOnBoard(b, from.row, from.col, tr, tc);
                }
                return false;
            case ROOK:
                if(isStraight(dr,dc)) return pathClearOnBoard(b, from.row, from.col, tr, tc);
                return false;
            case BISHOP:
                if(isDiagonal(dr,dc)) return pathClearOnBoard(b, from.row, from.col, tr, tc);
                return false;
            case KNIGHT:
                return (Math.abs(dr)==2 && Math.abs(dc)==1) || (Math.abs(dr)==1 && Math.abs(dc)==2);
            case PAWN:
                int dir = (p.color==PColor.WHITE) ? -1 : 1;
                return (dr==dir && Math.abs(dc)==1);
            default:
                return false;
        }
    }

    private boolean pathClearOnBoard(Piece[][] b, int fr, int fc, int tr, int tc) {
        int rStep = Integer.signum(tr - fr);
        int cStep = Integer.signum(tc - fc);
        int r = fr + rStep;
        int c = fc + cStep;
        while(r != tr || c != tc) {
            if(b[r][c] != null) return false;
            r += rStep; c += cStep;
        }
        return true;
    }

    private boolean isEnPassantCapture(Piece pawn, Position from, Position to) {
        if(pawn.type != PType.PAWN) return false;
        int dr = to.row - from.row;
        int dc = to.col - from.col;
        int dir = (pawn.color==PColor.WHITE)? -1 : 1;
        if(Math.abs(dc)==1 && dr==dir && board[to.row][to.col]==null && lastMove!=null && lastMovedPiece!=null && lastMovedPiece.type==PType.PAWN) {
            int lastFromRow = lastMove.from.row;
            int lastToRow = lastMove.to.row;
            int lastToCol = lastMove.to.col;
            if(Math.abs(lastFromRow - lastToRow)==2 && lastToRow==from.row && lastToCol==to.col && lastMovedPiece.color!=pawn.color) {
                return true;
            }
        }
        return false;
    }

    private void checkForGameEndByNoMoves(PColor color) {
        List<Move> moves = generateAllLegalMoves(color);
        if(moves.isEmpty()){
            boolean king = kingExists(color);
            if(!king || isKingInCheck(color)) {
                declareWinner(oppositeColor(color), (color==PColor.WHITE ? whiteName : blackName) + " has no legal moves (checkmate)");
            } else {
                declareDraw("Stalemate - " + (color==PColor.WHITE ? whiteName : blackName) + " has no legal moves");
            }
        }
    }
}
