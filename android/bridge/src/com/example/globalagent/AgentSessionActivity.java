package com.example.globalagent;

import android.app.Activity;
import android.app.KeyguardManager;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.PowerManager;
import android.text.Editable;
import android.text.InputFilter;
import android.text.TextWatcher;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class AgentSessionActivity extends Activity
    implements AgentSessionClient.Listener {
  private static final String TAG = "GlobalAgentSession";

  private final ExecutorService executor = Executors.newSingleThreadExecutor();
  private KeyguardManager keyguardManager;
  private PowerManager powerManager;
  private AgentSessionClient client;
  private TextView statusView;
  private EditText transcriptView;
  private Button startButton;
  private Button submitButton;
  private Button cancelButton;
  private boolean requestInFlight;
  private String requestError;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    keyguardManager = getSystemService(KeyguardManager.class);
    powerManager = getSystemService(PowerManager.class);
    setContentView(buildContentView());
  }

  @Override
  protected void onStart() {
    super.onStart();
    client = SessionClientRegistry.get();
    if (client != null) {
      client.addListener(this);
    }
    render(client == null ? null : client.snapshot());
  }

  @Override
  protected void onStop() {
    final AgentSessionClient current = client;
    final SessionStatus status = current == null ? null : current.snapshot();
    if (current != null) {
      current.removeListener(this);
    }
    client = null;
    if (!isChangingConfigurations() && current != null &&
        (requestInFlight || isActive(status))) {
      executor.execute(() -> cancelQuietly(current));
    }
    super.onStop();
  }

  @Override
  protected void onDestroy() {
    executor.shutdown();
    super.onDestroy();
  }

  @Override
  public void onStatusChanged(SessionStatus status) {
    runOnUiThread(() -> {
      requestInFlight = false;
      requestError = null;
      render(status);
    });
  }

  private View buildContentView() {
    final int spacing = Math.round(20 * getResources().getDisplayMetrics().density);
    final ScrollView scroll = new ScrollView(this);
    scroll.setFillViewport(true);
    final LinearLayout root = new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setGravity(Gravity.CENTER_HORIZONTAL);
    root.setPadding(spacing, spacing, spacing, spacing);

    final TextView title = new TextView(this);
    title.setText("Global Agent");
    title.setTextSize(24);
    title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
    root.addView(title, matchWidthWrapHeight());

    statusView = new TextView(this);
    statusView.setTextSize(16);
    statusView.setPadding(0, spacing, 0, spacing);
    root.addView(statusView, matchWidthWrapHeight());

    transcriptView = new EditText(this);
    transcriptView.setHint("Command");
    transcriptView.setSingleLine(false);
    transcriptView.setMaxLines(4);
    transcriptView.setFilters(new InputFilter[] {
        new InputFilter.LengthFilter(SessionEntryPolicy.MAX_TRANSCRIPT_BYTES)
    });
    transcriptView.addTextChangedListener(new TextWatcher() {
      @Override
      public void beforeTextChanged(CharSequence text, int start, int count,
          int after) {}

      @Override
      public void onTextChanged(CharSequence text, int start, int before,
          int count) {
        render(client == null ? null : client.snapshot());
      }

      @Override
      public void afterTextChanged(Editable text) {}
    });
    root.addView(transcriptView, matchWidthWrapHeight());

    startButton = new Button(this);
    startButton.setText("Start session");
    startButton.setOnClickListener(view -> beginSession());
    root.addView(startButton, matchWidthWrapHeight());

    submitButton = new Button(this);
    submitButton.setText("Submit command");
    submitButton.setOnClickListener(view -> submitTranscript());
    root.addView(submitButton, matchWidthWrapHeight());

    cancelButton = new Button(this);
    cancelButton.setText("Cancel");
    cancelButton.setOnClickListener(view -> cancelSession());
    root.addView(cancelButton, matchWidthWrapHeight());
    scroll.addView(root, new ScrollView.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT));
    return scroll;
  }

  private LinearLayout.LayoutParams matchWidthWrapHeight() {
    return new LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT);
  }

  private void beginSession() {
    final AgentSessionClient current = client;
    final SessionStatus status = current == null ? null : current.snapshot();
    if (!SessionEntryPolicy.canStart(status != null, isActive(status),
        isKeyguardLocked(), isDisplayInteractive(), requestInFlight)) {
      render(status);
      return;
    }
    requestInFlight = true;
    requestError = null;
    render(status);
    final Display display = getDisplay();
    final int displayId = display == null ? Display.DEFAULT_DISPLAY :
        display.getDisplayId();
    executor.execute(() -> runRequest(() -> current.beginExplicitSession(
        displayId, isKeyguardLocked(), true), false));
  }

  private void submitTranscript() {
    final AgentSessionClient current = client;
    final SessionStatus status = current == null ? null : current.snapshot();
    final String text = transcriptView.getText().toString();
    if (!SessionEntryPolicy.canSubmit(status != null, isActive(status),
        stateOf(status), requestInFlight, text)) {
      render(status);
      return;
    }
    requestInFlight = true;
    requestError = null;
    render(status);
    executor.execute(() -> runRequest(
        () -> current.submitTranscript(text, true), true));
  }

  private void cancelSession() {
    final AgentSessionClient current = client;
    final SessionStatus status = current == null ? null : current.snapshot();
    if (!SessionEntryPolicy.canCancel(status != null, isActive(status),
        requestInFlight)) {
      render(status);
      return;
    }
    requestInFlight = true;
    requestError = null;
    render(status);
    executor.execute(() -> runRequest(current::cancel, false));
  }

  private void runRequest(SessionRequest request, boolean clearTranscript) {
    try {
      final SessionStatus result = request.run();
      runOnUiThread(() -> {
        requestInFlight = false;
        requestError = null;
        if (clearTranscript) {
          transcriptView.setText("");
        }
        render(result);
      });
    } catch (Exception exception) {
      Log.w(TAG, "session request failed", exception);
      runOnUiThread(() -> {
        requestInFlight = false;
        requestError = "Request failed";
        render(client == null ? null : client.snapshot());
      });
    }
  }

  private void cancelQuietly(AgentSessionClient current) {
    try {
      final SessionStatus status = current.snapshot();
      if (status != null && status.active) {
        current.cancel();
      }
    } catch (Exception exception) {
      Log.w(TAG, "failed to cancel background session", exception);
    }
  }

  private void render(SessionStatus status) {
    if (statusView == null) {
      return;
    }
    final boolean connected = status != null;
    final boolean active = isActive(status);
    final boolean locked = isKeyguardLocked();
    final boolean interactive = isDisplayInteractive();
    statusView.setText(statusText(status, locked, interactive));
    startButton.setEnabled(SessionEntryPolicy.canStart(connected, active,
        locked, interactive, requestInFlight));
    submitButton.setEnabled(SessionEntryPolicy.canSubmit(connected, active,
        stateOf(status), requestInFlight,
        transcriptView.getText().toString()));
    cancelButton.setEnabled(SessionEntryPolicy.canCancel(connected, active,
        requestInFlight));
    transcriptView.setEnabled(active && stateOf(status) ==
        AgentSessionClient.STATE_LISTENING && !requestInFlight);
  }

  private String statusText(SessionStatus status, boolean locked,
      boolean interactive) {
    if (requestError != null) {
      return requestError;
    }
    if (requestInFlight) {
      return "Working";
    }
    if (!interactive) {
      return "Display inactive";
    }
    if (locked) {
      return "Device locked";
    }
    if (status == null) {
      return "Service unavailable";
    }
    switch (status.state) {
      case AgentSessionClient.STATE_IDLE:
        return "Idle";
      case AgentSessionClient.STATE_LISTENING:
        return "Listening";
      case AgentSessionClient.STATE_THINKING:
        return "Thinking";
      case AgentSessionClient.STATE_EXECUTING:
        return "Executing";
      case AgentSessionClient.STATE_FEEDBACK:
        return "Feedback";
      case AgentSessionClient.STATE_ERROR:
        return "Error";
      default:
        return "Unknown state";
    }
  }

  private boolean isKeyguardLocked() {
    return keyguardManager == null || keyguardManager.isKeyguardLocked();
  }

  private boolean isDisplayInteractive() {
    return powerManager != null && powerManager.isInteractive();
  }

  private static boolean isActive(SessionStatus status) {
    return status != null && status.active;
  }

  private static int stateOf(SessionStatus status) {
    return status == null ? AgentSessionClient.STATE_IDLE : status.state;
  }

  private interface SessionRequest {
    SessionStatus run() throws Exception;
  }
}
