package com.apps.amplifty;

import com.apps.amplifty.R;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;
import androidx.navigation.NavController;
import androidx.navigation.fragment.NavHostFragment;

import android.annotation.SuppressLint;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.text.TextUtils;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.view.MenuItem;
import android.view.View;
import android.view.translation.TranslationSpec;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.apps.amplifty.databinding.ActivityMainBinding;
import com.apps.amplifty.ui.help.HelpFragment;
import com.apps.amplifty.ui.home.HomeFragment;
import com.apps.amplifty.ui.myvoice.MyVoiceFragment;
import com.apps.amplifty.ui.settings.SettingsFragment;
import com.google.android.material.navigation.NavigationBarView;
import com.microsoft.cognitiveservices.speech.Recognizer;
import com.microsoft.cognitiveservices.speech.audio.AudioConfig;
import com.microsoft.cognitiveservices.speech.SpeechConfig;
import com.microsoft.cognitiveservices.speech.SpeechRecognizer;
import com.microsoft.cognitiveservices.speech.KeywordRecognitionModel;
import com.microsoft.cognitiveservices.speech.translation.SpeechTranslationConfig;
import com.microsoft.cognitiveservices.speech.translation.TranslationRecognitionResult;
import com.microsoft.cognitiveservices.speech.translation.TranslationRecognizer;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static android.Manifest.permission.INTERNET;
import static android.Manifest.permission.READ_EXTERNAL_STORAGE;
import static android.Manifest.permission.RECORD_AUDIO;

public class MainActivity extends AppCompatActivity {
    ActivityMainBinding binding;
    // pipeline for audio streaming
    AudioPipeStream streamer;
    private static final String SpeechSubscriptionKey = "aefea0592c9d43358f81419551a1deac";
    // Replace below with your own service region (e.g., "westus").
    private static final String SpeechRegion = "eastus";
    private static final String KwsModelFile = "kws.table";

    private String selectedLanguage = "Spanish";

    private TextView recognizedTextView;
    private Button translateContinuousButton;
    private Button recognizeContinuousButton;

    private MicrophoneStream microphoneStream;

    private MicrophoneStream createMicrophoneStream() {
        this.releaseMicrophoneStream();

        microphoneStream = new MicrophoneStream();

        return microphoneStream;
    }

    private void releaseMicrophoneStream() {
        if (microphoneStream != null) {
            microphoneStream.close();
            microphoneStream = null;
        }
    }

    private void startAudioStreaming() {
        // create audio streamer. This will also start it
        MainActivity.this.streamer = MainActivity.this.new AudioPipeStream();
        Log.i("Streamer", "Audio streamer started.");
    }

    private void stopAudioStreaming() {
        // stop audio streaming
        MainActivity.this.streamer.stahp();
        MainActivity.this.streamer.interrupt();
        try {
            MainActivity.this.streamer.join();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        Log.i("Streamer", "Audio streamer stopped.");
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.d("MainActivity", "onCreate");

        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        binding.navView.setOnItemSelectedListener(new NavigationBarView.OnItemSelectedListener() {
            @Override
            public boolean onNavigationItemSelected(@NonNull MenuItem item) {

                int itemId = item.getItemId();
                if (itemId == R.id.nav_btn_home) {
                    replaceFragment(new HomeFragment());
                } else if (itemId == R.id.nav_btn_my_voice) {
                    replaceFragment(new MyVoiceFragment());
                } else if (itemId == R.id.nav_btn_help) {
                    replaceFragment(new HelpFragment());
                } else if (itemId == R.id.nav_btn_settings) {
                    replaceFragment(new SettingsFragment());
                }

                return true;
            }
        });

        // language selection
        Spinner spinner = (Spinner) findViewById(R.id.spinner);
        // Create an ArrayAdapter using the string array and a default spinner layout
        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(this,
                R.array.languages_array, android.R.layout.simple_spinner_item);
        // Specify the layout to use when the list of choices appears
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        // Apply the adapter to the spinner
        spinner.setAdapter(adapter);

        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> adapterView, View view, int pos, long l) {
                selectedLanguage = adapterView.getItemAtPosition(pos).toString();
            }

            @Override
            public void onNothingSelected(AdapterView<?> adapterView) {
                selectedLanguage = "Spanish";
            }
        });

        recognizedTextView = findViewById(R.id.recognizedText);
        recognizedTextView.setMovementMethod(new ScrollingMovementMethod());

        translateContinuousButton = findViewById(R.id.buttonRecognizeContinuous2);
        translateContinuousButton.setOnClickListener(new View.OnClickListener() {
            private static final String logTag = "reco";
            private boolean continuousListeningStarted = false;
            private SpeechRecognizer reco = null;
            private TranslationRecognizer translatorReco = null;
            private AudioConfig audioInput = null;
            private String buttonText = "";
            private ArrayList<String> content = new ArrayList<>();
            private boolean clicked = false;

            @Override
            public void onClick(final View view) {
                final Button clickedButton = (Button) view;
                disableButtons();
                if (continuousListeningStarted) {
                    if (translatorReco != null) {
                        final Future<Void> task = translatorReco.stopContinuousRecognitionAsync();
                        setOnTaskCompletedListener(task, result -> {
                            Log.i(logTag, "Continuous recognition stopped.");
                            enableButtons();
                            continuousListeningStarted = false;

                            stopAudioStreaming();
                        });
                    } else {
                        continuousListeningStarted = false;
                    }

                    return;
                }

                clearTextBox();

                // start audio streaming
                startAudioStreaming();

                try {
                    content.clear();

                    audioInput = AudioConfig.fromStreamInput(createMicrophoneStream());

                    SpeechTranslationConfig speechTranslationConfig = SpeechTranslationConfig.fromSubscription(SpeechSubscriptionKey, SpeechRegion);
                    speechTranslationConfig.setSpeechRecognitionLanguage("en-US");

                    Map<String, String> languageCodeMap = new HashMap<String, String>() {{
                        put("Spanish", "es");
                        put("Italian", "it");
                        put("French", "fr");
                        put("Indian", "in");
                        put("Korean", "ko");
                        put("Japanese", "ja");
                        put("Chinese", "zh");
                        put("Russian", "ru");
                    }};

                    String toLanguage = languageCodeMap.get(selectedLanguage);

                    if(toLanguage == null){
                        return;
                    }

                    speechTranslationConfig.addTargetLanguage(toLanguage);

                    translatorReco = new TranslationRecognizer(speechTranslationConfig, audioInput);
                    ;
                    translatorReco.recognizing.addEventListener((o, speechTranslationResultEventArgs) -> {
                        final String s = speechTranslationResultEventArgs.getResult().getTranslations().get(toLanguage);
                        content.add(s);
                        setRecognizedText(TextUtils.join(" ", content));
                        content.remove(content.size() - 1);
                    });

                    translatorReco.recognized.addEventListener((o, speechTranslationResultEventArgs) -> {
                        final String s = speechTranslationResultEventArgs.getResult().getTranslations().get(toLanguage);
                        Log.d("MainActivity-recognized", s);
                        content.add(s);
                        setRecognizedText(TextUtils.join(" ", content));
                    });

                    final Future<Void> task = translatorReco.startContinuousRecognitionAsync();

                    setOnTaskCompletedListener(task, result -> {
                        continuousListeningStarted = true;
                        MainActivity.this.runOnUiThread(() -> {
                            clickedButton.setEnabled(true);
                        });
                    });

                } catch (Exception ex) {
                    System.out.println(ex.getMessage());
                    displayException(ex);
                }
            }
        });


        recognizeContinuousButton = findViewById(R.id.buttonRecognizeContinuous);
        // Initialize SpeechSDK and request required permissions.
        try {
            // a unique number within the application to allow
            // correlating permission request responses with the request.
            int permissionRequestId = 5;

            // Request permissions needed for speech recognition
            ActivityCompat.requestPermissions(MainActivity.this, new String[]{RECORD_AUDIO, INTERNET, READ_EXTERNAL_STORAGE}, permissionRequestId);
        } catch (Exception ex) {
            Log.e("SpeechSDK", "could not init sdk, " + ex.toString());
            recognizedTextView.setText("Could not initialize: " + ex.toString());
        }

        // create config
        final SpeechConfig speechConfig;
        final KeywordRecognitionModel kwsModel;
        try {
            speechConfig = SpeechConfig.fromSubscription(SpeechSubscriptionKey, SpeechRegion);
            kwsModel = KeywordRecognitionModel.fromFile(copyAssetToCacheAndGetFilePath(KwsModelFile));
        } catch (Exception ex) {
            System.out.println(ex.getMessage());
            displayException(ex);
            return;
        }

        ///////////////////////////////////////////////////
        // speech recognize continuously
        ///////////////////////////////////////////////////
        recognizeContinuousButton.setOnClickListener(new View.OnClickListener() {
            private static final String logTag = "reco";
            private boolean continuousListeningStarted = false;
            private SpeechRecognizer reco = null;
            private TranslationRecognizer translatorReco = null;
            private AudioConfig audioInput = null;
            private String buttonText = "";
            private ArrayList<String> content = new ArrayList<>();
            private boolean clicked = false;

            @Override
            public void onClick(final View view) {
                final Button clickedButton = (Button) view;
                disableButtons();
                if (continuousListeningStarted) {
                    if (reco != null) {
                        final Future<Void> task = reco.stopContinuousRecognitionAsync();
                        setOnTaskCompletedListener(task, result -> {
                            Log.i(logTag, "Continuous recognition stopped.");
                            enableButtons();
                            continuousListeningStarted = false;

                            // stop audio streaming
                            stopAudioStreaming();
                        });
                    } else {
                        continuousListeningStarted = false;
                    }

                    return;
                }

                clearTextBox();

                // start audio streaming
                startAudioStreaming();

                try {
                    content.clear();

                    audioInput = AudioConfig.fromStreamInput(createMicrophoneStream());


                    reco = new SpeechRecognizer(speechConfig, audioInput);
                    reco.recognizing.addEventListener((o, speechRecognitionResultEventArgs) -> {
                        final String s = speechRecognitionResultEventArgs.getResult().getText();
                        Log.i(logTag, "Intermediate result received: " + s);
                        content.add(s);
                        setRecognizedText(TextUtils.join(" ", content));
                        content.remove(content.size() - 1);
                    });

                    reco.recognized.addEventListener((o, speechRecognitionResultEventArgs) -> {
                        final String s = speechRecognitionResultEventArgs.getResult().getText();
                        Log.i(logTag, "Final result received: " + s);
                        content.add(s);
                        setRecognizedText(TextUtils.join(" ", content));
                    });

                    final Future<Void> task = reco.startContinuousRecognitionAsync();
                    setOnTaskCompletedListener(task, result -> {
                        continuousListeningStarted = true;
                        MainActivity.this.runOnUiThread(() -> {
                            clickedButton.setEnabled(true);
                        });
                    });


                } catch (Exception ex) {
                    System.out.println(ex.getMessage());
                    displayException(ex);
                }
            }
        });
    }

    private void replaceFragment(Fragment fragment) {
        FragmentManager fragmentManager = getSupportFragmentManager();
        FragmentTransaction fragmentTransaction = fragmentManager.beginTransaction();
//        fragmentTransaction.replace(R.id.nav_host_fragment_activity_main2, fragment);
        fragmentTransaction.commit();
    }

    private void displayException(Exception ex) {
        recognizedTextView.setText(ex.getMessage() + System.lineSeparator() + TextUtils.join(System.lineSeparator(), ex.getStackTrace()));
    }

    private void clearTextBox() {
        AppendTextLine("", true);
    }

    private void setRecognizedText(final String s) {
        AppendTextLine(s, true);
    }

    private void AppendTextLine(final String s, final Boolean erase) {
        MainActivity.this.runOnUiThread(() -> {
            if (erase) {
                recognizedTextView.setText(s);
            } else {
                String txt = recognizedTextView.getText().toString();
                recognizedTextView.setText(txt + System.lineSeparator() + s);
            }
        });
    }

    private void disableButtons() {
        MainActivity.this.runOnUiThread(() -> {
            translateContinuousButton.setEnabled(false);
            recognizeContinuousButton.setEnabled(false);
        });
    }

    private void enableButtons() {
        MainActivity.this.runOnUiThread(() -> {
            translateContinuousButton.setEnabled(true);
            recognizeContinuousButton.setEnabled(true);
        });
    }

    private <T> void setOnTaskCompletedListener(Future<T> task, OnTaskCompletedListener<T> listener) {
        s_executorService.submit(() -> {
            T result = task.get();
            listener.onCompleted(result);
            return null;
        });
    }

    private interface OnTaskCompletedListener<T> {
        void onCompleted(T taskResult);
    }

    private String copyAssetToCacheAndGetFilePath(String filename) {
        File cacheFile = new File(getCacheDir() + "/" + filename);
        if (!cacheFile.exists()) {
            try {
                InputStream is = getAssets().open(filename);
                int size = is.available();
                byte[] buffer = new byte[size];
                is.read(buffer);
                is.close();
                FileOutputStream fos = new FileOutputStream(cacheFile);
                fos.write(buffer);
                fos.close();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        return cacheFile.getPath();
    }

    private static ExecutorService s_executorService;

    static {
        s_executorService = Executors.newCachedThreadPool();
    }

    public class Word {
        public String word;
        public String errorType;
        public double accuracyScore;
        public long duration;
        public long offset;

        public Word(String word, String errorType) {
            this.word = word;
            this.errorType = errorType;
        }

        public Word(String word, String errorType, double accuracyScore, long duration, long offset) {
            this(word, errorType);
            this.accuracyScore = accuracyScore;
            this.duration = duration;
            this.offset = offset;
        }
    }

    private class AudioPipeStream extends Thread {
        private boolean running = false;

        private final static int SAMPLE_RATE = 16000;

        private AudioPipeStream() {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);
            start();
        }

        @Override
        public void run() {
            running = true;

            int N = 0;
            short[] buf;

            N = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);

            buf = new short[N];

            Log.i("SNDREC", "Buffer size = " + N);

            AudioRecord rec = null;
            AudioTrack trk = null;

            try {

                rec = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, N);
                trk = new AudioTrack(AudioManager.STREAM_MUSIC, SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT, N, AudioTrack.MODE_STREAM);
                rec.startRecording();
                trk.play();

                Log.i("SNDREC", "Start'd");

                while (running) {
                    // read from microphone
                    N = rec.read(buf, 0, buf.length);

                    // apply attenuation to reduce probability of howling
                    for (int i = 0; i < buf.length; i++)
                        buf[i] = (short) (buf[i] >> 4);

                    // output to speaker
                    trk.write(buf, 0, buf.length);
                }
                Log.i("SNDREC", "Stahp'd");
            } catch (Throwable t) {
                t.printStackTrace();
                Log.e("SNDREC", "RETVAL #" + N);
            } finally {
                rec.stop();
                rec.release();
                trk.stop();
                trk.release();
                Log.i("SNDREC", "Disposed");
            }
        }

        public void stahp() {
            running = false;
        }

    }
}
