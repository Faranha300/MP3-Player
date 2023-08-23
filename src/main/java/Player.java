import com.mpatric.mp3agic.InvalidDataException;
import com.mpatric.mp3agic.UnsupportedTagException;
import javazoom.jl.decoder.*;
import javazoom.jl.player.AudioDevice;
import javazoom.jl.player.FactoryRegistry;
import support.PlayerWindow;
import support.Song;

import javax.swing.event.MouseInputAdapter;
import java.awt.*;
import java.awt.event.ActionListener;
import java.awt.event.MouseEvent;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Random;
import java.util.concurrent.locks.ReentrantLock;

public class Player {

    /**
     * The MPEG audio bitstream.
     */
    private Bitstream bitstream;
    /**
     * The MPEG audio decoder.
     */
    private Decoder decoder;
    /**
     * The AudioDevice where audio samples are written to.
     */
    private AudioDevice device;
    private int currentFrame = 0; // Current frame of the music.
    private final ReentrantLock thread = new ReentrantLock(); // Lock.
    private PlayerWindow window; // The player window.
    private Song currentPlayingSong; // The current playing song in the player.
    private String[][] musicQueue; // Queue containing all songs in order.
    private ArrayList<Song> songs = new ArrayList<>(); // Array containing the ID of the songs.
    private String[][] unshuffledMusicQueue; // Queue containing all songs without the shuffle.
    private ArrayList<Song> unshuffledSongs = new ArrayList<>(); // Array containing the ID of the songs without the shuffle.
    private boolean shuffleActivated = false; // Indicates if shuffle is activated.
    private boolean loopActivated = false; // Indicates if loop is activated.
    private final Random random = new Random(); // Random.
    private boolean newPlay; // A boolean that indicates if a new song is about to play.
    private boolean dragged = false; // If the mouse drag the scrubber.
    Thread threadPlaying; // Thread of the playing method.
    private boolean playerEnabled = false; // Enable the player.
    private int playPauseButton = 0; // 0 == Play | 1 == Pause
    private static final String WINDOW_TITLE = "Music Player"; // The window title.

    private final ActionListener buttonListenerPlayNow = e -> playNow();
    private final ActionListener buttonListenerRemove = e -> remove();
    private final ActionListener buttonListenerAddSong = e -> add();
    private final ActionListener buttonListenerPlayPause = e -> playPause();
    private final ActionListener buttonListenerStop = e -> stop();
    private final ActionListener buttonListenerNext = e -> next();
    private final ActionListener buttonListenerPrevious = e -> previous();
    private final ActionListener buttonListenerShuffle = e -> shuffle();
    private final ActionListener buttonListenerLoop = e -> loop();
    private final MouseInputAdapter scrubberMouseInputAdapter = new MouseInputAdapter() {
        @Override
        public void mouseReleased(MouseEvent e) {scrubberRelease();}

        @Override
        public void mousePressed(MouseEvent e) {scrubberPress();}

        @Override
        public void mouseDragged(MouseEvent e) {scrubberDrag();}
    };

    public Player() {
        EventQueue.invokeLater(() -> window = new PlayerWindow(
                WINDOW_TITLE,
                this.musicQueue,
                buttonListenerPlayNow,
                buttonListenerRemove,
                buttonListenerAddSong,
                buttonListenerShuffle,
                buttonListenerPrevious,
                buttonListenerPlayPause,
                buttonListenerStop,
                buttonListenerNext,
                buttonListenerLoop,
                scrubberMouseInputAdapter)
        );
    }

    /**
     * Plays the current selected song.
     */
    private void playNow(){
        try {
            if (threadPlaying != null && threadPlaying.isAlive()) threadPlaying.interrupt(); // Interrupt the playing thread.
            String selectedSong = window.getSelectedSong();
            int currentSongIdx = 0; // The index of the current song.
            newPlay = true; // When a new song is about to play.

            for (int i = 0; i < musicQueue.length; i++){ // Update the mini player with the information of the music.
                if (selectedSong == musicQueue[i][5]){
                    window.setPlayingSongInfo(musicQueue[i][0], musicQueue[i][1], musicQueue[i][2]);
                    currentSongIdx = i;
                }
            }

            currentPlayingSong = songs.get(currentSongIdx); // Define current playing song.
            playerEnabled = true;
            playPauseButton = 1;

            /*
            * Enabling the buttons in mini player.
            */
            window.setPlayPauseButtonIcon(playPauseButton);
            window.setEnabledPlayPauseButton(playerEnabled);
            window.setEnabledStopButton(playerEnabled);
            window.setEnabledScrubber(playerEnabled);

            /*
            * Creating the Bitstream, Decoder and AudioDevice.
            */
            this.device = FactoryRegistry.systemRegistry().createAudioDevice();
            this.device.open(this.decoder = new Decoder());
            this.bitstream = new Bitstream(currentPlayingSong.getBufferedInputStream());

            skipToFrame(0);
            newPlay = false;
            currentFrame = 0;
            playing(currentPlayingSong);

        }
        catch (JavaLayerException | FileNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * A runnable that plays the song.
     */
    private void playing(Song currentSong){
        threadPlaying = new Thread(new Runnable() {
            @Override
            public void run() {
                int musicLength = currentSong.getNumFrames(); // Getting the length of the music in frames
                float musicMS = currentSong.getMsPerFrame();
                musicLength *= (int) (musicMS); // Converting frames to millisecond.

                while (playPauseButton == 1){ // While the player is not paused.
                    thread.lock();
                    try {
                        if (!dragged){
                            currentFrame += 1;
                            window.setTime((int) (currentFrame * musicMS), musicLength); // Setting the timer in the mini player.
                        }
                        verifyNextPrevious();

                        if (!playNextFrame()){ // If have no more frames to play, the next song will play.
                            if (!loopActivated) {
                                if (currentSong == songs.get(songs.size() - 1)) {
                                    stop();
                                } else {
                                    next(); // Go to the next music in the queue
                                }
                            }
                            else { // If the loop is activated.
                                next();
                            }
                        }

                        if (newPlay){ // If a new song is about to play, stops the current one.
                            break;
                        }

                    } catch (JavaLayerException e) {
                        throw new RuntimeException(e);
                    }
                    finally {
                        thread.unlock();
                    }
                }
            }
        });
        threadPlaying.start();
    }

    /**
     * Removes the current selected song from the playlist.
     */
    private void remove(){
        String selectedSong = window.getSelectedSong();
        int queueLength = musicQueue.length; // Length of the queue.
        String[][] tempQueue = new String[queueLength - 1][6]; // Creating a queue with -1 length of the musicQueue.
        String[][] tempUnshuffledQueue = new String[queueLength - 1][6];
        int newIndex = 0; // Defines the new index for each music in the queue.
        int newUnshuffledIndex = 0; // Defines the new index for each music in the unshuffled queue.

        for (int i = 0; i < queueLength; i++) {
            if (selectedSong != musicQueue[i][5]) { // Remove the song in the queue.
                tempQueue[newIndex] = musicQueue[i];
                newIndex++;
            }
            else{
                if (currentPlayingSong == songs.get(i) || loopActivated){
                    next(); // If the song is not the last one, jump to the next song.
                }
                if (currentPlayingSong == songs.get(songs.size()-1)){
                    stop(); // Stops the song, if the playing song is the last in the queue.
                }
                songs.remove(i);
            }
            if (shuffleActivated) { // Remove the song in the unshuffled queue.
                if (selectedSong != unshuffledMusicQueue[i][5]) {
                    tempUnshuffledQueue[newUnshuffledIndex] = unshuffledMusicQueue[i];
                    newUnshuffledIndex++;
                } else {
                    unshuffledSongs.remove(i);
                }
            }
        }

        musicQueue = tempQueue;
        unshuffledMusicQueue = tempUnshuffledQueue;
        verifyShuffleLoop();
        window.setQueueList(musicQueue); // Update the playlist
    }

    /**
     * Used to add songs to the playlist.
     */
    private void add() {
        try {
            Song song = window.openFileChooser(); // Used to get the current added song.

            if (song != null) {
                String[] currentSong = song.getDisplayInfo(); // Get the information of the song.
                int queueLength = musicQueue != null ? musicQueue.length : 0; // Getting the length of the queue.
                String[][] tempQueue = new String[queueLength + 1][6]; // Creating a queue with +1 length of the musicQueue.
                String[][] tempUnshuffledQueue = new String[queueLength + 1][6];

                for (int i = 0; i < queueLength; i++) { // Transfer all the elements in the music queue to the temp queue.
                    tempQueue[i] = musicQueue[i];
                    if (shuffleActivated){
                        tempUnshuffledQueue[i] = unshuffledMusicQueue[i];
                    }
                }

                songs.add(song); // adding the new song into the queue.
                tempQueue[queueLength] = currentSong;
                musicQueue = tempQueue;
                if (shuffleActivated){ // adding the new song into the unshuffled queue.
                    unshuffledSongs.add(song);
                    tempUnshuffledQueue[queueLength] = currentSong;
                    unshuffledMusicQueue = tempUnshuffledQueue;
                }
                verifyShuffleLoop();
                window.setQueueList(musicQueue); // Update the playlist.
            }
        }
        catch (IOException | BitstreamException | UnsupportedTagException | InvalidDataException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Play and pause the current playing song.
     */
    private void playPause(){
        if (playPauseButton == 1){ // Pause the song.
            playPauseButton = 0;
            window.setPlayPauseButtonIcon(playPauseButton);
        }
        else{ // Resume the song.
            playPauseButton = 1;
            window.setPlayPauseButtonIcon(playPauseButton);
            playing(currentPlayingSong);
        }

    }

    /**
     * Stop the reproduction of the current playing song and return the player to default.
     */
    private void stop(){
        playerEnabled = false;
        playPauseButton = 0;
        window.resetMiniPlayer();
    }

    /**
     * Jump to the previous song in the queue.
     */
    private void previous() {
        try {
            if (currentPlayingSong != songs.get(0) || loopActivated) {
                if (threadPlaying != null && threadPlaying.isAlive())
                    threadPlaying.interrupt(); // Interrupt the playing thread.
                newPlay = true;
                int previousSongIndex = songs.size() - 1; // Index of the previous song in the queue.

                if (currentPlayingSong != songs.get(0) || !loopActivated) {
                    for (int i = 0; i < songs.size(); i++) {
                        if (currentPlayingSong == songs.get(i)) { // Update the mini player and get the index of the song.
                            window.setPlayingSongInfo(musicQueue[i - 1][0], musicQueue[i - 1][1], musicQueue[i - 1][2]);
                            previousSongIndex = i - 1;
                        }
                    }
                }
                else {
                    window.setPlayingSongInfo(musicQueue[previousSongIndex][0], musicQueue[previousSongIndex][1], musicQueue[previousSongIndex][2]);
                }
                currentPlayingSong = songs.get(previousSongIndex); // Define current playing song.

                /*
                * Creating the Bitstream, Decoder and AudioDevice.
                */
                this.device = FactoryRegistry.systemRegistry().createAudioDevice();
                this.device.open(this.decoder = new Decoder());
                this.bitstream = new Bitstream(currentPlayingSong.getBufferedInputStream());

                skipToFrame(0);
                newPlay = false;
                currentFrame = 0;
                if (playPauseButton == 0){ // If the player is paused, jump to the song and resume.
                    playPause();
                }
                else {
                    playing(currentPlayingSong);
                }
            }
        }
        catch (JavaLayerException | FileNotFoundException e){
            throw new RuntimeException(e);
        }
    }

    /**
     * Jump to the next song in the queue.
     */
    private void next() {
        try {
            if (currentPlayingSong != songs.get(songs.size() - 1) || loopActivated) {
                if (threadPlaying != null && threadPlaying.isAlive())
                    threadPlaying.interrupt(); // Interrupt the playing thread.
                newPlay = true;
                int nextSongIndex = 0; // Index of the next song in the queue.

                if (currentPlayingSong != songs.get(songs.size() - 1) || !loopActivated) {
                    for (int i = 0; i < songs.size(); i++) {
                        if (currentPlayingSong == songs.get(i)) { // Update the mini player and get the index of the song.
                            window.setPlayingSongInfo(musicQueue[i + 1][0], musicQueue[i + 1][1], musicQueue[i + 1][2]);
                            nextSongIndex = i + 1;
                        }
                    }
                }
                else {
                    window.setPlayingSongInfo(musicQueue[0][0], musicQueue[0][1], musicQueue[0][2]);
                }
                currentPlayingSong = songs.get(nextSongIndex); // Define current playing song.

                /*
                 * Creating the Bitstream, Decoder and AudioDevice.
                 */
                this.device = FactoryRegistry.systemRegistry().createAudioDevice();
                this.device.open(this.decoder = new Decoder());
                this.bitstream = new Bitstream(currentPlayingSong.getBufferedInputStream());

                skipToFrame(0);
                newPlay = false;
                currentFrame = 0;
                if (playPauseButton == 0) { // If the player is paused, jump to the song and resume.
                    playPause();
                } else {
                    playing(currentPlayingSong);
                }
            }
        }
        catch (JavaLayerException | FileNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Used to activate shuffle in the queue.
     */
    private void shuffle() {
        if (!shuffleActivated) { // If shuffle is not activated
            unshuffledMusicQueue = Arrays.copyOf(musicQueue, musicQueue.length);
            unshuffledSongs = new ArrayList<>(songs);
            int indexCurrentPlaying = 0;

            Song[] songsArray = new Song[songs.size()];
            for (int i = 0; i < songs.size(); i++) {
                songsArray[i] = songs.get(i);
            }

            for (int i = 0; i < songs.size(); i++) {
                int index = random.nextInt(i + 1); // Generate a random index

                Song temp1 = songsArray[i]; // Swap the songs array.
                songsArray[i] = songsArray[index];
                songsArray[index] = temp1;

                String[] temp2 = musicQueue[i]; // Swap the music queue.
                musicQueue[i] = musicQueue[index];
                musicQueue[index] = temp2;
            }

            if (threadPlaying != null && threadPlaying.isAlive()){ // If thread playing is alive, the current playing song go to the head of the queue.
                for (int i = 0; i < songs.size(); i++){ // Get the index of the current song in the shuffled queue.
                    if (songsArray[i] == currentPlayingSong){
                        indexCurrentPlaying = i;
                    }
                }
                Song temp1 = songsArray[0]; // Swap the songs array.
                songsArray[0] = songsArray[indexCurrentPlaying];
                songsArray[indexCurrentPlaying] = temp1;

                String[] temp2 = musicQueue[0]; // Swap the music queue.
                musicQueue[0] = musicQueue[indexCurrentPlaying];
                musicQueue[indexCurrentPlaying] = temp2;
            }

            songs.clear();
            Collections.addAll(songs, songsArray);
            window.setQueueList(musicQueue);
            shuffleActivated = true;
        }
        else { // If shuffle is already activated
            musicQueue = Arrays.copyOf(unshuffledMusicQueue, unshuffledMusicQueue.length);
            songs = new ArrayList<>(unshuffledSongs);

            window.setQueueList(musicQueue);
            shuffleActivated = false;
        }
    }

    /**
     * Used to activate the loop in the queue.
     */
    private void loop(){
        loopActivated = !loopActivated;
    }

    /**
     * Checks if is possible to shuffle or loop the playlist.
     */
    private void verifyShuffleLoop(){
        window.setEnabledShuffleButton(songs.size() > 1);
        window.setEnabledLoopButton(songs.size() > 0);
    }

    /**
     * Checks if is possible to skip or rewind songs.
     */
    private void verifyNextPrevious(){
        if (loopActivated){ // Always turn on previous and next buttons, if loop is activated.
            window.setEnabledPreviousButton(true);
            window.setEnabledNextButton(true);
        }
        else {
            /*
             * if only have one song.
             */
            if (currentPlayingSong == songs.get(0) && currentPlayingSong == songs.get(songs.size() - 1)) {
                window.setEnabledPreviousButton(false);
                window.setEnabledNextButton(false);
            }
            /*
             * If only have song to skip.
             */
            if (currentPlayingSong == songs.get(0) && currentPlayingSong != songs.get(songs.size() - 1)) {
                window.setEnabledPreviousButton(false);
                window.setEnabledNextButton(true);
            }
            /*
             * If only have song to rewind.
             */
            if (currentPlayingSong != songs.get(0) && currentPlayingSong == songs.get(songs.size() - 1)) {
                window.setEnabledPreviousButton(true);
                window.setEnabledNextButton(false);
            }
            /*
             * If have songs to skip and rewind.
             */
            if (currentPlayingSong != songs.get(0) && currentPlayingSong != songs.get(songs.size() - 1)) {
                window.setEnabledPreviousButton(true);
                window.setEnabledNextButton(true);
            }
        }
    }

    private void scrubberDrag(){
        dragged = true;
        currentFrame = window.getScrubberValue(); // Get the time in the scrubber.
        window.setTime(currentFrame, (int) currentPlayingSong.getMsLength()); // Update the mini player
    }

    private void scrubberRelease(){
       try {
           if (threadPlaying != null && threadPlaying.isAlive()) threadPlaying.interrupt();
           newPlay = true;

           device = FactoryRegistry.systemRegistry().createAudioDevice();
           device.open(decoder = new Decoder());
           bitstream = new Bitstream(currentPlayingSong.getBufferedInputStream());

           int scrubberTime = window.getScrubberValue(); // Get the time in the scrubber.
           float musicMS = currentPlayingSong.getMsPerFrame();
           int newFrame = (int) (scrubberTime/musicMS); // Get the new frame of the song.

           currentFrame = 0;
           skipToFrame(newFrame); // Skip the song to the new frame.
           currentFrame = newFrame;
           dragged = false;
           newPlay = false;
           playing(currentPlayingSong); // Return the song at the new frame.

       } catch (FileNotFoundException | JavaLayerException e) {
           throw new RuntimeException(e);
       }
    }

    private void scrubberPress(){
        dragged = true;
        currentFrame = window.getScrubberValue(); // Get the time in the scrubber.
        window.setTime(currentFrame, (int) currentPlayingSong.getMsLength()); // Update the mini player
    }

    //<editor-fold desc="Essential">

    /**
     * @return False if there are no more frames to play.
     */
    private boolean playNextFrame() throws JavaLayerException {
        // TODO: Is this thread safe?
        if (device != null) {
            Header h = bitstream.readFrame();
            if (h == null) return false;

            SampleBuffer output = (SampleBuffer) decoder.decodeFrame(h, bitstream);
            device.write(output.getBuffer(), 0, output.getBufferLength());
            bitstream.closeFrame();
        }
        return true;
    }

    /**
     * @return False if there are no more frames to skip.
     */
    private boolean skipNextFrame() throws BitstreamException {
        // TODO: Is this thread safe?
        Header h = bitstream.readFrame();
        if (h == null) return false;
        bitstream.closeFrame();
        currentFrame++;
        return true;
    }

    /**
     * Skips bitstream to the target frame if the new frame is higher than the current one.
     *
     * @param newFrame Frame to skip to.
     * @throws BitstreamException Generic Bitstream exception.
     */
    private void skipToFrame(int newFrame) throws BitstreamException {
        // TODO: Is this thread safe?
        if (newFrame > currentFrame) {
            int framesToSkip = newFrame - currentFrame;
            boolean condition = true;
            while (framesToSkip-- > 0 && condition) condition = skipNextFrame();
        }
    }
    //</editor-fold>
}
