package com.brutus.pepper

import android.Manifest
import android.animation.ObjectAnimator
import android.app.AlertDialog
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.input.InputManager
import android.os.Bundle
import android.os.Debug
import android.os.Handler
import android.os.SystemClock
import android.os.Looper
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.EditText
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.aldebaran.qi.sdk.QiContext
import com.aldebaran.qi.sdk.QiSDK
import com.aldebaran.qi.sdk.RobotLifecycleCallbacks
import com.aldebaran.qi.sdk.design.activity.RobotActivity
import java.util.Locale

class MainActivity : RobotActivity(), RobotLifecycleCallbacks, InputManager.InputDeviceListener {

    // ── Controllers / stores ────────────────────────────────────────────────
    private lateinit var hubController: HubController
    private lateinit var whisperController: WhisperBenchmarkController
    private lateinit var customKeycodeStore: CustomKeycodeStore
    private lateinit var inputManager: InputManager
    private lateinit var preferencesController: PreferencesController
    private lateinit var brainSettingsStore: BrainSettingsStore
    private lateinit var brain: BrainClient
    private var brainInfo: BrainInfo? = null
    private var foreground = false
    private var pendingTalk = false
    private var voiceError: String? = null
    private var wakeReady = false
    private var captureGeneration = 0L
    private lateinit var streaming: StreamingTranscriber
    private var streamCapture: StreamingTranscriber.Capture? = null
    private val orb: VoiceOrbView get() = findViewById(R.id.voiceOrb)

    // ── Conversation ────────────────────────────────────────────────────────
    private val conversationHistory = ConversationHistory()

    // ── Robot ───────────────────────────────────────────────────────────────
    private var speechGateway: SpeechGateway? = null
    private var drive: PepperDriveController? = null
    private var actionCoordinator: RobotActionCoordinator? = null
    private var recognizer: WhisperRecognizer? = null
    private var modelInitializationStarted = false
    private var controllerName: String? = null

    // ── Gamepad state ───────────────────────────────────────────────────────
    private val inputMapper = GamepadInputMapper()
    private val driveState = GamepadDriveState()
    private val recorder = PcmAudioRecorder()
    private val pressedControls = mutableSetOf<GamepadControl>()

    // ── Rebind state ────────────────────────────────────────────────────────
    private var rebindTarget: GamepadControl? = null
    private val rebindHandler = Handler(Looper.getMainLooper())
    private var rebindAnimator: ObjectAnimator? = null
    private lateinit var sceneRenderer: AndroidSceneRenderer
    private lateinit var sceneController: SceneController
    private lateinit var imageCarousel: ImageCarouselController

    // ── Écran d'accueil en mode hospitalité ─────────────────────────────────
    // L'image que le cerveau désigne occupe la tablette entre deux visiteurs. Un
    // battement régulier la pose, la retire et redemande au cerveau laquelle montrer,
    // pour qu'un changement fait depuis la webapp se voie sans redémarrer le robot.
    private val idleImages = IdleImageController()
    private val idleHandler = Handler(Looper.getMainLooper())
    private var lastIdlePollMs = 0L
    private val idleTick = object : Runnable {
        override fun run() {
            refreshIdleImage()
            idleHandler.postDelayed(this, IDLE_TICK_MS)
        }
    }

    // ── §3 — Auto-engage ────────────────────────────────────────────────────
    private var engagementListener: HumanEngagementListener? = null
    // Retient l'orientation vers laquelle Pepper se remet entre deux visiteurs.
    private var homePosition: HomePositionController? = null
    // True once Pepper has auto-engaged in the current conversation; blocks re-engaging until
    // the conversation ends (reset on conversation end / manual reset).
    private var engagedThisConversation = false
    // While elapsedRealtime() < this, a conversation is considered "active": auto-engage is blocked
    // (prevents Pepper re-introducing itself mid-conversation). Extended on every turn state change.
    private var conversationActiveUntilMs = 0L
    // Current conversation state (for the contextual Stop/Cancel button).
    private var currentConvState: ConversationController.State = ConversationController.State.IDLE_WAKE
    // Bumped when the user cancels a response; in-flight LLM/TTS callbacks compare and bail.
    private var responseGen = 0L

    // ── §4 — Conversation mode + wake-word ──────────────────────────────────
    private var wakeWordEngine: WakeWordEngine? = null
    private var conversationController: ConversationController? = null
    // Single shared Vosk model — used by both the wake-word engine and command transcription.
    private lateinit var voskModelHolder: VoskModelHolder

    // ── Panels ──────────────────────────────────────────────────────────────
    private val panels by lazy {
        mapOf(
            HubScreen.HOME        to findViewById<View>(R.id.homePanel),
            HubScreen.ACTIONS     to findViewById(R.id.actionsPanel),
            HubScreen.GAMEPAD     to findViewById(R.id.gamepadPanel),
            HubScreen.PROVIDER    to findViewById(R.id.providerPanel),
            HubScreen.PREFERENCES to findViewById(R.id.preferencesPanel)
        )
    }

    /** Entrées du rail, pour marquer celle de la page ouverte. */
    private val navItems by lazy {
        mapOf(
            HubScreen.HOME        to findViewById<View>(R.id.navHome),
            HubScreen.ACTIONS     to findViewById(R.id.navActions),
            HubScreen.GAMEPAD     to findViewById(R.id.navGamepad),
            HubScreen.PROVIDER    to findViewById(R.id.navProvider),
            HubScreen.PREFERENCES to findViewById(R.id.navPreferences)
        )
    }

    // ────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ────────────────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Unique porte de sortie : la tablette ne parle qu'au cerveau, qui détient les
        // clés. Le même client sert la conversation, la transcription et les médias.
        // Créé avant le rendu, qui a besoin du jeton pour télécharger les médias.
        brainSettingsStore = EncryptedBrainSettingsStore(this)
        sceneRenderer = AndroidSceneRenderer(this) { brainSettingsStore.load() }

        brain = BrainClient(
            settingsProvider = { brainSettingsStore.load() },
            history = conversationHistory
        )
        recognizer = brain
        streaming = StreamingTranscriber({ brainSettingsStore.load() }, brain)

        sceneController = SceneController(brain, sceneRenderer)
        imageCarousel = ImageCarouselController(
            resolve = { query, callback -> brain.resolveImage(query, callback) },
            display = { media, durationMs, onComplete ->
                sceneController.showImageUrl(media.url, media.name, durationMs, onComplete)
            },
            clear = { sceneController.cancel() },
            dispatch = { action -> runOnUiThread(action) },
            onFailure = { action, error ->
                android.util.Log.w("BrutusScene", "image « ${action.query} » introuvable", error)
            },
        )
        findViewById<View>(R.id.presentationOverlay).setOnClickListener { dismissOverlay() }

        hubController = HubController(SharedPreferencesBindingStore(this))
        whisperController = WhisperBenchmarkController()
        customKeycodeStore = CustomKeycodeStore(this)
        inputManager = getSystemService(Context.INPUT_SERVICE) as InputManager
        preferencesController = PreferencesController(PreferencesStore(this))
        // Start unpacking the shared Vosk model immediately (slow ~85 MB) so it's ready by
        // the time the user toggles conversation mode or presses PTT.
        voskModelHolder = VoskModelHolder(applicationContext)

        inputMapper.customKeycodes = customKeycodeStore.load()

        // React to preference changes: apply autoEngage and conversationMode behavioral effects.
        preferencesController.onChanged = { state ->
            applyAutoEngage(state.autoEngage)
            applyConversationMode(state.conversationMode)
            renderConversationToggles()
        }

        bindNavigation()
        bindGamepadControls()
        bindActionMenu()
        bindVoiceControls()
        bindPreferenceControls()
        bindBrainControls()
        renderHub()
        renderWhisper()
        QiSDK.register(this, this)

        // Apply persisted conversation mode at startup (onChanged does NOT fire at init).
        // Vosk only needs the mic permission; if already granted, arm the wake-word now.
        if (preferencesController.state.conversationMode &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            applyConversationMode(true)
        }
    }

    override fun onResume() {
        super.onResume()
        foreground = true
        inputManager.registerInputDeviceListener(this, null)
        refreshGamepads()
        applyConversationMode(preferencesController.state.conversationMode)
        updateInterruptButton(currentConvState)
        // Revenir au premier plan est un geste : on laisse le délai courir avant de
        // reposer l'image, sinon elle recouvrirait l'écran qu'on vient d'ouvrir.
        idleImages.noteActivity(SystemClock.elapsedRealtime())
        idleHandler.removeCallbacks(idleTick)
        idleHandler.postDelayed(idleTick, IDLE_TICK_MS)
        if (pendingTalk && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            pendingTalk = false
            startConversation()
        }
    }

    override fun onPause() {
        foreground = false
        idleHandler.removeCallbacks(idleTick)
        haltVoice()
        inputManager.unregisterInputDeviceListener(this)
        driveState.reset()
        actionCoordinator?.onGamepadDisconnected()
        recorder.stop()
        cancelRebind()
        // §4 — Stop Vosk when activity goes to background (mic must not be held)
        wakeWordEngine?.stop()
        super.onPause()
    }

    override fun onDestroy() {
        idleHandler.removeCallbacks(idleTick)
        if (::streaming.isInitialized) streaming.close()
        recorder.stop()
        recognizer?.close()
        sceneRenderer.close()
        actionCoordinator?.onRobotFocusLost()
        // §4 — Release Vosk engine and disable conversation controller
        conversationController?.disable()
        wakeWordEngine?.release()
        wakeWordEngine = null
        conversationController = null
        QiSDK.unregister(this, this)
        super.onDestroy()
    }

    // ────────────────────────────────────────────────────────────────────────
    // Robot lifecycle
    // ────────────────────────────────────────────────────────────────────────

    override fun onRobotFocusGained(qiContext: QiContext) {
        val speech = QiSpeechGateway(qiContext)
        val newDrive = PepperDriveController(qiContext)
        speechGateway = speech
        drive = newDrive
        // L'installateur pose le robot face à l'entrée : c'est la référence par
        // défaut, et elle est remplaçable depuis Réglages une fois le robot déplacé.
        homePosition = HomePositionController(qiContext).also { it.remember() }

        // §3 — Create engagement listener first.
        var coordinator: RobotActionCoordinator? = null
        val listener = HumanEngagementListener(
            qiContext = qiContext,
            // Auto-engage only to START a conversation: ON pref, not already engaged this
            // conversation, and the conversation is still short (≤ 2 messages). Stops Pepper
            // re-engaging mid-conversation.
            shouldEngage = {
                // Block auto-engage while a conversation is active OR within the grace window after
                // one — this is what stops Pepper re-introducing itself mid-conversation when it
                // re-detects a human (or re-acquires the same person).
                val convBusy = conversationController?.awaitingExplicitWake == true ||
                    conversationController?.isBusy() == true ||
                    SystemClock.elapsedRealtime() < conversationActiveUntilMs
                if (!convBusy && engagedThisConversation) {
                    // Conversation truly over → reset so the next (new) person can be greeted fresh.
                    engagedThisConversation = false
                    conversationHistory.reset()
                }
                foreground && preferencesController.state.conversationMode && preferencesController.state.autoEngage &&
                    conversationController?.awaitingExplicitWake != true &&
                    !engagedThisConversation &&
                    !convBusy &&
                    conversationHistory.getMessages().size <= 2
            },
            engage = {
                val ok = coordinator?.run(BoundAction.ENGAGE_HUMAN) == true
                if (ok) {
                    engagedThisConversation = true
                    conversationActiveUntilMs = SystemClock.elapsedRealtime() + CONVERSATION_GRACE_MS
                }
                ok
            }
        )
        engagementListener = listener

        coordinator = RobotActionCoordinator(
            drive = newDrive,
            actions = QiRobotActions(qiContext),
            onActionCompleted = { action, result ->
                if (action == BoundAction.ENGAGE_HUMAN && result.isSuccess &&
                    conversationController?.awaitingExplicitWake != true) {
                    // L'accroche est demandée au cerveau à chaque personne, pas une fois
                    // au démarrage : couper l'hospitalité depuis la webapp prend ainsi
                    // effet au visiteur suivant, sans redémarrer la tablette.
                    val greetingGen = responseGen
                    brain.greeting { greetingResult ->
                        val hospitality = greetingResult.getOrNull()
                        if (greetingResult.isFailure) {
                            android.util.Log.w(
                                "BrutusBrain", "accroche indisponible, accueil habituel",
                                greetingResult.exceptionOrNull()
                            )
                        }
                        runOnUiThread { if (foreground && preferencesController.state.conversationMode &&
                            greetingGen == responseGen && conversationController?.awaitingExplicitWake != true &&
                            conversationController?.isBusy() != true) {
                            if (hospitality?.isUsable == true) speakGreeting(hospitality.speech, hospitality.actions)
                            else speakGreeting(DEFAULT_GREETINGS.random())
                        } }
                    }
                }
            },
            onIdle = {
                if (preferencesController.state.autoEngage) {
                    listener.checkEngagement()
                }
            }
        )
        actionCoordinator = coordinator

        runOnUiThread {
            coordinator.onRobotFocusGained()
            if (controllerName != null) coordinator.onGamepadConnected()
            updateRobotStatus("Pepper est prêt")
            applyConversationMode(preferencesController.state.conversationMode)
            // Apply persisted autoEngage state now that we have focus (onChanged doesn't fire at init)
            if (preferencesController.state.autoEngage) listener.start()
        }
    }

    override fun onRobotFocusLost() {
        runOnUiThread { haltVoice() }
        // §3 — Stop and clear the engagement listener (it holds a reference to qiContext)
        engagementListener?.stop()
        engagementListener = null

        runOnUiThread {
            driveState.reset()
            actionCoordinator?.onRobotFocusLost()
            drive?.close()
            homePosition?.cancel()
            homePosition = null
            actionCoordinator = null
            drive = null
            speechGateway = null
            updateRobotStatus("En attente de Pepper")
        }
    }

    override fun onRobotFocusRefused(reason: String) = onRobotFocusLost()

    // ────────────────────────────────────────────────────────────────────────
    // Input devices
    // ────────────────────────────────────────────────────────────────────────

    override fun onInputDeviceAdded(deviceId: Int) = refreshGamepads()
    override fun onInputDeviceRemoved(deviceId: Int) = refreshGamepads()
    override fun onInputDeviceChanged(deviceId: Int) = refreshGamepads()

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        val device = event.device ?: return super.onGenericMotionEvent(event)
        if (event.source and InputDevice.SOURCE_JOYSTICK != InputDevice.SOURCE_JOYSTICK)
            return super.onGenericMotionEvent(event)
        val leftX = centeredAxis(event, device, MotionEvent.AXIS_X)
        val leftY = centeredAxis(event, device, MotionEvent.AXIS_Y)
        val hatX = centeredAxis(event, device, MotionEvent.AXIS_HAT_X)
        sendDriveAxes(driveState.updateMotion(leftX, leftY, hatX))
        inputMapper.triggerEdge(GamepadControl.LEFT_TRIGGER,
            centeredAxis(event, device, MotionEvent.AXIS_LTRIGGER))?.let { edge ->
            if (edge.pressed) pressedControls.add(edge.control) else pressedControls.remove(edge.control)
            renderGamepadHighlights()
            dispatchGamepadEdge(edge)
        }
        inputMapper.triggerEdge(GamepadControl.RIGHT_TRIGGER,
            centeredAxis(event, device, MotionEvent.AXIS_RTRIGGER))?.let { edge ->
            if (edge.pressed) pressedControls.add(edge.control) else pressedControls.remove(edge.control)
            renderGamepadHighlights()
            dispatchGamepadEdge(edge)
        }
        return true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        dpadDirection(keyCode)?.let { direction ->
            if (event.repeatCount == 0) sendDriveAxes(driveState.updateKey(direction, true))
            return true
        }

        // Mode remapping: capture the next physical key press
        val target = rebindTarget
        if (target != null) {
            customKeycodeStore.save(keyCode, target)
            inputMapper.customKeycodes = customKeycodeStore.load()
            cancelRebind()
            Toast.makeText(applicationContext, "${target.shortLabel} remappé ✓", Toast.LENGTH_SHORT).show()
            return true
        }

        val control = inputMapper.controlForKeyCode(keyCode) ?: return super.onKeyDown(keyCode, event)
        pressedControls.add(control)
        renderGamepadHighlights()
        if (event.repeatCount == 0) dispatchGamepadEdge(GamepadEdge(control, true))
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        dpadDirection(keyCode)?.let { direction ->
            sendDriveAxes(driveState.updateKey(direction, false))
            return true
        }

        val control = inputMapper.controlForKeyCode(keyCode) ?: return super.onKeyUp(keyCode, event)
        pressedControls.remove(control)
        renderGamepadHighlights()
        dispatchGamepadEdge(GamepadEdge(control, false))
        return true
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (requestCode == MICROPHONE_REQUEST) {
            val granted = results.firstOrNull() == PackageManager.PERMISSION_GRANTED
            whisperController.onPermissionChanged(granted)
            renderWhisper()
            if (granted) {
                initializeWhisper()
                // §4 — Apply persisted conversationMode now that mic permission is confirmed
                applyConversationMode(preferencesController.state.conversationMode)
                if (pendingTalk && foreground) { pendingTalk = false; startConversation() }
            } else {
                pendingTalk = false
                preferencesController.setConversationMode(false)
                voiceError = "Autorisez le microphone dans les réglages Android."
                updateInterruptButton(currentConvState)
            }
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // Menu d'actions
    // ────────────────────────────────────────────────────────────────────────

    /**
     * Un bouton par action exécutable. Construit une fois, à partir de
     * [ActionMenu.runnable] : une action ajoutée un jour à l'énumération apparaîtra
     * d'elle-même, sans qu'on ait à toucher à cet écran.
     */
    private fun bindActionMenu() {
        val list = findViewById<LinearLayout>(R.id.actionsList)
        list.removeAllViews()
        for (action in ActionMenu.runnable()) {
            val button = TextView(this)
            button.text = action.label
            button.textSize = 20f
            button.setBackgroundResource(R.drawable.hello_button_background)
            button.setTextColor(getColor(R.color.on_accent))
            button.gravity = Gravity.CENTER
            button.isClickable = true
            button.isFocusable = true
            button.setPadding(dp(18), dp(20), dp(18), dp(20))
            button.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, dp(6), 0, dp(6)) }
            button.setOnClickListener { runFromMenu(action) }
            list.addView(button)
        }
    }

    /**
     * Lance une action et dit ce qui se passe.
     *
     * Le coordinateur refuse poliment quand le robot n'a pas la main ou qu'un
     * mouvement est déjà en cours. Sans retour à l'écran, la personne appuierait à
     * nouveau en croyant avoir mal visé.
     */
    private fun runFromMenu(action: BoundAction) {
        val status = findViewById<TextView>(R.id.actionsStatus)
        val coordinator = actionCoordinator
        if (coordinator == null) {
            status.text = "Pepper n'est pas encore prêt."
            status.setTextColor(getColor(R.color.danger))
            return
        }
        if (coordinator.run(action)) {
            status.text = "« ${action.label} » en cours…"
            status.setTextColor(getColor(R.color.ok))
        } else {
            status.text = "Pepper est occupé — réessayez dans un instant."
            status.setTextColor(getColor(R.color.ink_2))
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // Navigation
    // ────────────────────────────────────────────────────────────────────────

    private fun bindNavigation() {
        findViewById<View>(R.id.navToggle).setOnClickListener {
            setNavigationVisible(findViewById<View>(R.id.navigationRail).visibility != View.VISIBLE)
        }
        setNavigationVisible(false)
        findViewById<View>(R.id.navHome).setOnClickListener        { navigate(HubScreen.HOME) }
        findViewById<View>(R.id.navActions).setOnClickListener      { navigate(HubScreen.ACTIONS) }
        findViewById<View>(R.id.navGamepad).setOnClickListener     { navigate(HubScreen.GAMEPAD) }
        findViewById<View>(R.id.navProvider).setOnClickListener    { navigate(HubScreen.PROVIDER) }
        findViewById<View>(R.id.navPreferences).setOnClickListener { navigate(HubScreen.PREFERENCES) }
    }

    private fun setNavigationVisible(visible: Boolean) {
        val rail = findViewById<View>(R.id.navigationRail)
        rail.visibility = if (visible) View.VISIBLE else View.GONE
        findViewById<android.widget.TextView>(R.id.navToggle).apply {
            text = getString(if (visible) R.string.close_navigation else R.string.open_navigation)
            contentDescription = text
        }
        val inset = if (visible) rail.layoutParams.width else 0
        listOf(R.id.mainPanels, R.id.presentationOverlay).forEach { id ->
            findViewById<View>(id).apply {
                layoutParams = (layoutParams as android.widget.FrameLayout.LayoutParams).apply {
                    marginEnd = inset
                }
            }
        }
    }

    private fun navigate(screen: HubScreen) {
        // Aller dans un écran est un geste : l'accueil ne doit pas revenir par-dessus.
        dismissOverlay()
        hubController.navigateTo(screen)
        renderHub()
        if (screen == HubScreen.HOME) ensureWhisperReady()
    }

    // ────────────────────────────────────────────────────────────────────────
    // Render
    // ────────────────────────────────────────────────────────────────────────

    private fun renderHub() {
        val current = hubController.state.screen
        panels.forEach { (screen, panel) ->
            panel.visibility = if (screen == current) View.VISIBLE else View.GONE
        }
        // Sans ça, les quatre entrées du rail sont identiques et rien ne dit sur quelle
        // page on se trouve — c'est la première question que pose quelqu'un devant l'écran.
        navItems.forEach { (screen, item) -> item.isSelected = screen == current }
        bindingViews().forEach { (control, view) -> view.text = control.shortLabel }
    }

    private fun renderGamepadHighlights() {
        bindingViews().forEach { (control, view) ->
            view.isActivated = control in pressedControls
        }
    }

    private fun renderWhisper() {
        val state = whisperController.state
        updateRobotStatus(when (state.phase) {
            WhisperPhase.PERMISSION_REQUIRED -> "Autorisation microphone requise"
            WhisperPhase.LOADING             -> "Connexion à l’antenne…"
            WhisperPhase.READY               -> "Connecté à l’antenne"
            WhisperPhase.RECORDING           -> "Enregistrement… relâchez pour transcrire"
            WhisperPhase.TRANSCRIBING        -> "Transcription en cours…"
            WhisperPhase.ERROR               -> state.error ?: "Erreur Whisper"
        })
        // Timing details belong in diagnostics, not the visitor’s conversation.
        findViewById<TextView>(R.id.voiceMetrics).text =
            if (brainInfo != null) "Conversation reliée à votre antenne." else "Votre échange apparaît ici."
    }

    private fun updateRobotStatus(text: String) {
        runOnUiThread { findViewById<TextView>(R.id.statusText).text = text }
    }

    // ────────────────────────────────────────────────────────────────────────
    // Chat bubbles
    // ────────────────────────────────────────────────────────────────────────

    private fun addUserBubble(text: String) {
        runOnUiThread {
            findViewById<View>(R.id.conversationEmpty).visibility = View.GONE
            findViewById<View>(R.id.liveTranscript).visibility = View.GONE
            findViewById<LinearLayout>(R.id.chatContainer).addView(makeBubble(text, isUser = true))
            scrollChatToBottom()
        }
    }

    private fun addPepperBubble(text: String) {
        runOnUiThread {
            findViewById<View>(R.id.conversationEmpty).visibility = View.GONE
            findViewById<LinearLayout>(R.id.chatContainer).addView(makeBubble(text, isUser = false))
            scrollChatToBottom()
        }
    }

    private fun clearChatUi() {
        runOnUiThread {
            findViewById<LinearLayout>(R.id.chatContainer).removeAllViews()
            findViewById<View>(R.id.conversationEmpty).visibility = View.VISIBLE
            findViewById<View>(R.id.liveTranscript).visibility = View.GONE
        }
    }

    private fun makeBubble(text: String, isUser: Boolean): TextView {
        val tv = TextView(this)
        tv.text = text
        tv.textSize = 19f
        // Convention de messagerie : ce que dit le visiteur est à droite dans la couleur
        // de marque, ce que répond Pepper est à gauche sur une carte blanche. Tout le
        // monde la connaît, personne n'a à l'apprendre.
        tv.setTextColor(getColor(if (isUser) R.color.on_accent else R.color.ink))
        tv.setLineSpacing(0f, 1.25f)
        tv.setPadding(dp(18), dp(14), dp(18), dp(14))
        tv.setBackgroundResource(if (isUser) R.drawable.bubble_user else R.drawable.bubble_pepper)
        tv.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = if (isUser) Gravity.END else Gravity.START
            // La marge opposée empêche une bulle longue de traverser tout l'écran : au-delà
            // d'une certaine largeur, une ligne de texte devient pénible à suivre.
            setMargins(
                if (isUser) dp(18) else 0,
                dp(7),
                if (isUser) 0 else dp(18),
                dp(7)
            )
        }
        return tv
    }

    private fun scrollChatToBottom() {
        val messages = findViewById<LinearLayout>(R.id.chatContainer)
        while (messages.childCount > 20) messages.removeViewAt(0)
        findViewById<ScrollView>(R.id.chatScrollView).post {
            findViewById<ScrollView>(R.id.chatScrollView).fullScroll(View.FOCUS_DOWN)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    // ────────────────────────────────────────────────────────────────────────
    // Gamepad — binding + remapping
    // ────────────────────────────────────────────────────────────────────────

    private fun bindGamepadControls() {
        bindingViews().forEach { (control, view) ->
            view.setOnClickListener {
                if (rebindTarget != null) cancelRebind()
                else showBindingMenu(control, view)
            }
        }
    }

    private fun showBindingMenu(control: GamepadControl, view: View) {
        val options = arrayOf("🔁 Remapper ce bouton PS4",
            *BoundAction.entries.map { it.label }.toTypedArray())
        AlertDialog.Builder(this)
            .setTitle(control.label)
            .setItems(options) { _, index ->
                if (index == 0) startRebind(control, view)
                else {
                    hubController.bind(control, BoundAction.entries[index - 1])
                    renderHub()
                }
            }
            .show()
    }

    private fun startRebind(control: GamepadControl, targetView: View) {
        rebindTarget = control
        if (hubController.state.screen == HubScreen.GAMEPAD) {
            findViewById<TextView>(R.id.gamepadStatus).text =
                "Remapping [${control.label}] — appuie sur un bouton PS4…"
        }
        rebindAnimator = ObjectAnimator.ofFloat(targetView, "alpha", 0.3f, 1f).apply {
            duration = 500
            repeatCount = ObjectAnimator.INFINITE
            repeatMode = ObjectAnimator.REVERSE
            start()
        }
        rebindHandler.postDelayed({ cancelRebind() }, 8_000)
    }

    private fun cancelRebind() {
        rebindTarget = null
        rebindAnimator?.cancel()
        rebindAnimator = null
        rebindHandler.removeCallbacksAndMessages(null)
        runOnUiThread {
            bindingViews().values.forEach { it.alpha = 1f }
            refreshGamepads()
        }
    }

    private fun bindingViews(): Map<GamepadControl, TextView> = mapOf(
        GamepadControl.A             to findViewById(R.id.bindA),
        GamepadControl.B             to findViewById(R.id.bindB),
        GamepadControl.X             to findViewById(R.id.bindX),
        GamepadControl.Y             to findViewById(R.id.bindY),
        GamepadControl.LEFT_BUMPER   to findViewById(R.id.bindLeftBumper),
        GamepadControl.RIGHT_BUMPER  to findViewById(R.id.bindRightBumper),
        GamepadControl.LEFT_TRIGGER  to findViewById(R.id.bindLeftTrigger),
        GamepadControl.RIGHT_TRIGGER to findViewById(R.id.bindRightTrigger),
        GamepadControl.SELECT        to findViewById(R.id.bindSelect),
        GamepadControl.START         to findViewById(R.id.bindStart)
    )

    private fun dispatchGamepadEdge(edge: GamepadEdge) {
        val action = hubController.state.bindings[edge.control] ?: BoundAction.NONE
        when {
            action == BoundAction.PUSH_TO_TALK                  -> { if (edge.pressed) startPtt() else stopPtt() }
            action == BoundAction.RESET_CONTEXT && edge.pressed -> resetConversation()
            edge.pressed                                        -> actionCoordinator?.run(action)
        }
    }

    private fun refreshGamepads() {
        val device = inputManager.inputDeviceIds.asSequence()
            .mapNotNull { InputDevice.getDevice(it) }
            .firstOrNull(::isGamepad)
        controllerName = device?.name
        if (rebindTarget == null) {
            findViewById<TextView>(R.id.gamepadStatus).text =
                device?.let { "Connectée : ${it.name}" }
                    ?: "Aucune manette détectée — Bluetooth Android"
        }
        if (device == null) actionCoordinator?.onGamepadDisconnected()
        else actionCoordinator?.onGamepadConnected()  // toujours relancer après pause/resume
    }

    private fun isGamepad(device: InputDevice): Boolean =
        device.sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
            device.sources and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK

    private fun sendDriveAxes(axes: DriveAxes) {
        android.util.Log.d(
            "PepperDrive",
            "DRIVE lx=${axes.leftX} ly=${axes.leftY} dpadYaw=${axes.yaw} " +
                "drive=${if (drive != null) "SET" else "NULL"}"
        )
        drive?.updateTarget(axes.leftX, axes.leftY, axes.yaw, 0f)
    }

    private fun dpadDirection(keyCode: Int): DpadDirection? = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_LEFT -> DpadDirection.LEFT
        KeyEvent.KEYCODE_DPAD_RIGHT -> DpadDirection.RIGHT
        else -> null
    }

    // ────────────────────────────────────────────────────────────────────────
    // Voice / PTT
    // ────────────────────────────────────────────────────────────────────────

    private fun bindVoiceControls() {
        findViewById<View>(R.id.stopButton).setOnClickListener {
            when (currentConvState) {
                ConversationController.State.LISTENING -> recorder.stop()
                ConversationController.State.IDLE_WAKE -> startConversation()
                else -> { cancelCurrentResponse(); startConversation() }
            }
        }
        findViewById<View>(R.id.resetButton).setOnClickListener { resetConversation() }
        findViewById<View>(R.id.sceneTalk).setOnClickListener { findViewById<View>(R.id.stopButton).performClick() }
        findViewById<View>(R.id.sceneReturn).setOnClickListener { dismissOverlay() }
        findViewById<Switch>(R.id.sceneMicroSwitch).setOnCheckedChangeListener { _, checked ->
            preferencesController.setConversationMode(checked)
        }
        findViewById<Switch>(R.id.homeConversationSwitch).setOnCheckedChangeListener { _, checked ->
            preferencesController.setConversationMode(checked)
        }
        renderConversationToggles()
        updateInterruptButton(currentConvState)
    }

    private fun startConversation() {
        if (!foreground) return
        voiceError = null
        if (!brainSettingsStore.load().isComplete) {
            hubController.navigateTo(HubScreen.PROVIDER); renderHub()
            updateBrainStatus("Connectez votre antenne pour commencer.", Tone.ERROR)
            return
        }
        if (speechGateway == null) {
            voiceError = "En attente de connexion au robot."
            updateInterruptButton(currentConvState)
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingTalk = true
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), MICROPHONE_REQUEST)
            return
        }
        preferencesController.setConversationMode(true)
        applyConversationMode(true)
        conversationController?.triggerDirectListening(CONVERSATION_ONSET_TIMEOUT_MS)
    }

    /** Invalidates callbacks before releasing mic, network and speech resources. */
    private fun haltVoice() {
        if (::streaming.isInitialized) streaming.cancel()
        responseGen++
        captureGeneration++
        streamCapture?.cancel()
        streamCapture = null
        conversationController?.disable()
        whisperController.onTranscriptionFailed("")
        recorder.stop()
        wakeWordEngine?.stop()
        brain.cancelPending()
        speechGateway?.cancel()
        clearOverlay()
        currentConvState = ConversationController.State.IDLE_WAKE
        orb.setAudioLevel(0)
        findViewById<View>(R.id.liveTranscript).visibility = View.GONE
        updateInterruptButton(currentConvState)
    }

    private fun cancelCurrentResponse() {
        if (::streaming.isInitialized) streaming.cancel()
        responseGen++
        captureGeneration++
        streamCapture?.cancel()
        streamCapture = null
        brain.cancelPending()
        speechGateway?.cancel()
        clearOverlay()
        conversationController?.cancelTurn()
        currentConvState = ConversationController.State.IDLE_WAKE
        findViewById<View>(R.id.liveTranscript).visibility = View.GONE
        updateInterruptButton(currentConvState)
    }

    private fun renderConversationToggles() = runOnUiThread {
        val on = preferencesController.state.conversationMode
        findViewById<Switch>(R.id.homeConversationSwitch).let {
            if (it.isChecked != on) it.isChecked = on
            it.text = if (on) "Micro actif  " else "Micro coupé  "
        }
        findViewById<Switch>(R.id.conversationSwitch).let { if (it.isChecked != on) it.isChecked = on }
        findViewById<Switch>(R.id.sceneMicroSwitch).let { if (it.isChecked != on) it.isChecked = on }
    }

    private fun updateInterruptButton(st: ConversationController.State) = runOnUiThread {
        currentConvState = st
        val enabled = foreground && preferencesController.state.conversationMode
        val mode = when {
            st == ConversationController.State.IDLE_WAKE && voiceError != null -> VoiceMode.ERROR
            !enabled && st == ConversationController.State.IDLE_WAKE -> VoiceMode.MUTED
            st == ConversationController.State.LISTENING -> VoiceMode.LISTENING
            st == ConversationController.State.SPEAKING -> VoiceMode.SPEAKING
            st == ConversationController.State.THINKING || st == ConversationController.State.TRANSCRIBING -> VoiceMode.PROCESSING
            else -> VoiceMode.READY
        }
        orb.mode = mode
        findViewById<VoiceOrbView>(R.id.sceneVoiceOrb).mode = mode
        if (mode != VoiceMode.LISTENING) {
            // Un partiel décrit la prise en cours ; il ne doit pas rester affiché
            // après la fin, un silence ou une annulation.
            findViewById<View>(R.id.liveTranscript).visibility = View.GONE
        }
        val title = when (mode) {
            VoiceMode.MUTED -> "Micro coupé"
            VoiceMode.READY -> if (wakeReady) "Dites « Pepper »" else "Préparation de l’écoute"
            VoiceMode.LISTENING -> "Je vous écoute"
            VoiceMode.PROCESSING -> if (st == ConversationController.State.TRANSCRIBING) "Je vous ai entendu" else "Je réfléchis"
            VoiceMode.SPEAKING -> "À mon tour"
            VoiceMode.ERROR -> "Reprenons ensemble"
        }
        findViewById<TextView>(R.id.voiceState).text = title
        findViewById<TextView>(R.id.sceneVoiceState).text = title
        findViewById<TextView>(R.id.voiceHint).text = when (mode) {
            VoiceMode.MUTED -> "Touchez Parler pour commencer."
            VoiceMode.READY -> "Ou touchez Parler, tout simplement."
            VoiceMode.LISTENING -> "Prenez votre temps. Je suis là."
            VoiceMode.PROCESSING -> "Je prépare votre réponse."
            VoiceMode.SPEAKING -> "Touchez le bouton pour reprendre la parole."
            VoiceMode.ERROR -> voiceError
        }
        findViewById<TextView>(R.id.stopButton).apply {
            isEnabled = true
            text = when (mode) {
                VoiceMode.LISTENING -> "J’ai terminé"
                VoiceMode.PROCESSING, VoiceMode.SPEAKING -> "Reprendre la parole"
                else -> "Parler"
            }
        }
        findViewById<android.widget.ProgressBar>(R.id.audioLevelBar).apply {
            visibility = if (mode == VoiceMode.LISTENING || mode == VoiceMode.READY) View.VISIBLE else View.INVISIBLE
            if (visibility != View.VISIBLE) progress = 0
        }
        findViewById<TextView>(R.id.sceneTalk).text = findViewById<TextView>(R.id.stopButton).text
    }

    private fun setVoiceState(text: String) = runOnUiThread {
        val st = when {
            text.contains("écoute", true) -> ConversationController.State.LISTENING
            text.contains("Transcription", true) -> ConversationController.State.TRANSCRIBING
            text.contains("réfléchit", true) -> ConversationController.State.THINKING
            text.contains("parle", true) -> ConversationController.State.SPEAKING
            else -> ConversationController.State.IDLE_WAKE
        }
        updateInterruptButton(st)
    }

    private fun showListeningUi(listening: Boolean) {
        updateInterruptButton(if (listening) ConversationController.State.LISTENING else ConversationController.State.TRANSCRIBING)
    }

    private fun updateAudioLevel(rms: Int) = runOnUiThread {
        if (!foreground) return@runOnUiThread
        orb.setAudioLevel(rms)
        findViewById<VoiceOrbView>(R.id.sceneVoiceOrb).setAudioLevel(rms)
        findViewById<android.widget.ProgressBar>(R.id.audioLevelBar).progress = (rms / 25).coerceIn(0, 100)
    }

    private fun ensureWhisperReady() {
        val granted = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        whisperController.onPermissionChanged(granted)
        renderWhisper()
        if (!granted) requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), MICROPHONE_REQUEST)
        else initializeWhisper()
    }

    /** Vérifie que le cerveau répond et qu'un fournisseur y est configuré. */
    private fun initializeWhisper() {
        if (modelInitializationStarted || whisperController.state.modelReady) return
        if (!brainSettingsStore.load().isComplete) {
            whisperController.onModelFailed("Cerveau non appairé — page Cerveau")
            renderWhisper()
            return
        }
        modelInitializationStarted = true
        brain.hello { result ->
            runOnUiThread {
                result.onSuccess { info ->
                    brainInfo = info
                    if (info.isReady) {
                        whisperController.onModelReady(0L, memoryMb())
                    } else {
                        modelInitializationStarted = false
                        whisperController.onModelFailed(
                            "Aucun modèle choisi sur le cerveau — voir sa page web")
                    }
                }.onFailure {
                    modelInitializationStarted = false
                    whisperController.onModelFailed(it.message ?: "Cerveau injoignable")
                }
                renderBrainPanel()
                renderWhisper()
            }
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // Preferences panel wiring
    // ────────────────────────────────────────────────────────────────────────

    private fun bindPreferenceControls() {
        val checkAutoEngage = findViewById<CheckBox>(R.id.autoEngageCheck)
        val switchConversation = findViewById<Switch>(R.id.conversationSwitch)

        renderPreferences()

        checkAutoEngage.setOnCheckedChangeListener { _, isChecked ->
            preferencesController.setAutoEngage(isChecked)
        }
        switchConversation.setOnCheckedChangeListener { _, isChecked ->
            preferencesController.setConversationMode(isChecked)
        }
        findViewById<Switch>(R.id.returnHomeSwitch).setOnCheckedChangeListener { _, isChecked ->
            preferencesController.setReturnHome(isChecked)
        }
        findViewById<View>(R.id.setHomeButton).setOnClickListener { rememberHomePosition() }
        renderHomeStatus()
    }

    /** « Là où il est maintenant, c'est sa place. » */
    private fun rememberHomePosition() {
        val controller = homePosition
        val status = findViewById<TextView>(R.id.homeStatus)
        if (controller == null) {
            status.text = "En attente de Pepper : réessayez quand le robot est prêt."
            status.setTextColor(getColor(R.color.ink_2))
            return
        }
        if (controller.remember()) {
            status.text = "Position de départ enregistrée. Pepper y reviendra entre deux visiteurs."
            status.setTextColor(getColor(R.color.ok))
        } else {
            status.text = "Position non enregistrée. Vérifiez que Pepper est bien connecté."
            status.setTextColor(getColor(R.color.danger))
        }
    }

    private fun renderHomeStatus() = runOnUiThread {
        val switch = findViewById<Switch>(R.id.returnHomeSwitch)
        val wanted = preferencesController.state.returnHome
        if (switch.isChecked != wanted) switch.isChecked = wanted
    }

    private fun renderPreferences() {
        val state = preferencesController.state
        val checkAutoEngage = findViewById<CheckBox>(R.id.autoEngageCheck)
        val switchConversation = findViewById<Switch>(R.id.conversationSwitch)
        if (checkAutoEngage.isChecked != state.autoEngage) checkAutoEngage.isChecked = state.autoEngage
        if (switchConversation.isChecked != state.conversationMode) switchConversation.isChecked = state.conversationMode
        val switchHome = findViewById<Switch>(R.id.returnHomeSwitch)
        if (switchHome.isChecked != state.returnHome) switchHome.isChecked = state.returnHome
    }

    // ────────────────────────────────────────────────────────────────────────
    // Appairage au cerveau
    // ────────────────────────────────────────────────────────────────────────

    private fun bindBrainControls() {
        val settings = brainSettingsStore.load()
        findViewById<EditText>(R.id.brainAddress).setText(settings.baseUrl)
        findViewById<EditText>(R.id.brainToken).setText(settings.pairingToken)

        findViewById<View>(R.id.brainSave).setOnClickListener {
            val entered = BrainSettings(
                baseUrl = findViewById<EditText>(R.id.brainAddress).text.toString(),
                pairingToken = findViewById<EditText>(R.id.brainToken).text.toString().trim()
            )
            if (!entered.isComplete) {
                updateBrainStatus("Adresse et jeton sont tous les deux nécessaires", Tone.ERROR)
                return@setOnClickListener
            }
            haltVoice()
            brainSettingsStore.save(entered)
            // Le handshake est la seule preuve que l'appairage marche : on le tente
            // tout de suite plutôt que de laisser l'installateur le découvrir en
            // parlant au robot.
            updateBrainStatus("Connexion au cerveau…")
            modelInitializationStarted = false
            brain.hello { result ->
                runOnUiThread {
                    result.onSuccess { info ->
                        brainInfo = info
                        whisperController.onModelReady(0L, memoryMb())
                        if (info.isReady) {
                            updateBrainStatus("Appairé — ${info.llmModel}", Tone.OK)
                        } else {
                            updateBrainStatus(
                                "Appairé, mais aucun modèle n'est choisi sur le cerveau", Tone.ERROR)
                        }
                    }.onFailure {
                        brainInfo = null
                        updateBrainStatus(it.message ?: "Cerveau injoignable", Tone.ERROR)
                    }
                    renderBrainPanel()
                    renderWhisper()
                }
            }
        }
    }

    private fun renderBrainPanel() {
        runOnUiThread {
            val info = brainInfo
            findViewById<TextView>(R.id.brainDetails).text = when {
                info == null -> "Non connecté"
                !info.isReady -> "Connecté — aucun modèle choisi sur le cerveau"
                else -> "Conversation : ${info.llmModel}\n" +
                    "Transcription : ${info.sttModel} (${info.sttProvider})\n" +
                    "Médias disponibles : ${info.mediaCount}"
            }
        }
    }

    /**
     * Ton du message d'appairage. La couleur double le texte, elle ne le remplace pas :
     * l'installateur lit « Appairé » ou la cause de l'échec dans tous les cas.
     */
    private enum class Tone { NEUTRAL, OK, ERROR }

    private fun updateBrainStatus(text: String, tone: Tone = Tone.NEUTRAL) {
        runOnUiThread {
            val view = findViewById<TextView>(R.id.brainStatus)
            view.text = text
            view.setTextColor(getColor(when (tone) {
                Tone.OK -> R.color.ok
                Tone.ERROR -> R.color.danger
                Tone.NEUTRAL -> R.color.blue_dark
            }))
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // Hook points for §3 / §4 agent
    // ────────────────────────────────────────────────────────────────────────

    /**
     * §3 — Wire HumanEngagementListener.
     * Called whenever autoEngage preference changes (live toggle via onChanged).
     * Also called in onRobotFocusGained for the persisted startup value.
     */
    private fun applyAutoEngage(enabled: Boolean) {
        android.util.Log.d("Preferences", "applyAutoEngage: $enabled")
        val listener = engagementListener
        if (listener == null) {
            // No robot focus yet — the preference is stored; it will be applied in onRobotFocusGained
            return
        }
        if (enabled) listener.start() else listener.stop()
    }

    /**
     * §4 — Wire ConversationController / WakeWordEngine.
     * Called whenever conversationMode preference changes (live toggle via onChanged),
     * and also after mic permission is granted (see onRequestPermissionsResult).
     */
    private fun applyConversationMode(enabled: Boolean) {
        android.util.Log.d("Preferences", "applyConversationMode: $enabled")
        if (enabled && foreground) {
            // Guard: mic permission must be granted before Vosk can run
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), MICROPHONE_REQUEST)
                return
            }
            // Create engine and controller if not yet done
            if (wakeWordEngine == null) {
                val engine = WakeWordEngine(voskModelHolder,
                    onLevel = { updateAudioLevel(it) },
                    onListening = { ready -> runOnUiThread {
                        wakeReady = ready
                        if (currentConvState == ConversationController.State.IDLE_WAKE) updateInterruptButton(currentConvState)
                    } },
                    onUnavailable = { runOnUiThread {
                        if (foreground && preferencesController.state.conversationMode &&
                            currentConvState == ConversationController.State.IDLE_WAKE) {
                            voiceError = "Le mot d’éveil est indisponible. Touchez Parler."
                            updateInterruptButton(currentConvState)
                        }
                    } }) {
                    // onWake callback — invoked on the Vosk audio thread; ConversationController
                    // dispatches to the main thread internally
                    conversationController?.onWake()
                }
                wakeWordEngine = engine
                conversationController = ConversationController(conversationHost, engine).apply {
                    onConversationEnded = {
                        // Keep a grace window after a conversation: a brief pause shouldn't let Pepper
                        // immediately re-engage. shouldEngage resets the engage gate once it elapses.
                        conversationActiveUntilMs = SystemClock.elapsedRealtime() + CONVERSATION_GRACE_MS
                    }
                    onStateChanged = { st ->
                        runOnUiThread {
                            currentConvState = st
                            // Chaque changement d'état est un signe de vie : il relance le
                            // délai, y compris le retour au repos qui clôt une conversation.
                            idleImages.noteActivity(SystemClock.elapsedRealtime())
                            // Retirée tout de suite, sans attendre le battement : personne ne
                            // doit parler à Pepper devant son écran d'accueil.
                            if (st != ConversationController.State.IDLE_WAKE) {
                                hideIdleImage()
                                homePosition?.cancel()
                            }
                            if (st != ConversationController.State.IDLE_WAKE) {
                                conversationActiveUntilMs = SystemClock.elapsedRealtime() + CONVERSATION_GRACE_MS
                            }
                            setVoiceState(when (st) {
                                ConversationController.State.LISTENING    -> "🎤 À l'écoute…"
                                ConversationController.State.TRANSCRIBING -> "📝 Transcription…"
                                ConversationController.State.THINKING     -> "💭 Pepper réfléchit…"
                                ConversationController.State.SPEAKING     -> "🔊 Pepper parle…"
                                ConversationController.State.IDLE_WAKE    ->
                                    if (preferencesController.state.conversationMode) "Dis « Pepper » pour parler" else ""
                            })
                            updateInterruptButton(st)
                        }
                    }
                }
            }
            conversationController?.enable()
        } else {
            haltVoice()
        }
    }

    /**
     * ConversationHost implementation — bridges ConversationController with MainActivity's
     * recorder, recognizer, and respond pipeline.
     */
    private val conversationHost = object : ConversationHost {
        override fun listen(timeoutMs: Long, onResult: (ShortArray) -> Unit) {
            voiceError = null
            val captureGen = ++captureGeneration
            recorder.stopAndThen {
                runOnUiThread {
                    if (captureGen != captureGeneration || !foreground) return@runOnUiThread
                    val capture = streaming.begin { partial ->
                        runOnUiThread partialUpdate@{
                            if (captureGen != captureGeneration || !foreground ||
                                currentConvState != ConversationController.State.LISTENING) return@partialUpdate
                            findViewById<TextView>(R.id.liveTranscript).apply {
                                text = partial
                                visibility = if (partial.isBlank()) View.GONE else View.VISIBLE
                            }
                        }
                    }
                    streamCapture = capture
                    recorder.start(
                        gateFactory = { newSpeechGate(timeoutMs.toInt()) },
                        maxDurationMs = MAX_UTTERANCE_MS,
                        onLevel = { updateAudioLevel(it) },
                        onChunk = { capture.offer(it) }
                    ) { result ->
                        runOnUiThread captured@{
                            if (captureGen != captureGeneration || !foreground) return@captured
                            if (result.isFailure) voiceError = "Microphone indisponible. Réessayez."
                            val samples = result.getOrDefault(ShortArray(0))
                            if (samples.size < 6400) { capture.cancel(); streamCapture = null }
                            onResult(samples)
                        }
                    }
                }
            }
        }

        /** Le portier de la fenêtre d'écoute : voir [AdaptiveSpeechGate]. */
        private fun newSpeechGate(onsetTimeoutMs: Int): SpeechGate =
            AdaptiveSpeechGate(
                onsetTimeoutMs = onsetTimeoutMs,
                maxDurationMs = MAX_UTTERANCE_MS.toInt()
            )

        override fun transcribe(samples: ShortArray, onText: (String?) -> Unit) {
            val capture = streamCapture
            streamCapture = null
            val gen = captureGeneration
            val started = SystemClock.elapsedRealtime()
            val callback: (Result<WhisperResult>) -> Unit = { result ->
                runOnUiThread {
                    if (gen == captureGeneration && foreground) {
                        val text = result.getOrNull()?.text?.trim()
                        android.util.Log.i("PepperLatency", "finalisation_ms=" + (SystemClock.elapsedRealtime() - started))
                        if (result.isFailure) voiceError = "L’antenne n’a pas pu transcrire. Réessayez."
                        onText(if (text.isNullOrBlank()) null else text)
                    }
                }
            }
            if (capture == null) brain.transcribe(samples, callback)
            else capture.finish(samples, callback)
        }

        override fun respond(transcript: String, onSpoken: () -> Unit) {
            runOnUiThread {
                addUserBubble(transcript)
                respondWithControlPlane(transcript, onSpoken)
            }
        }
    }

    private fun startPtt() {
        if (!foreground) return
        if (!whisperController.state.modelReady) {
            ensureWhisperReady()
            return
        }
        if (!whisperController.onPttPressed()) return
        cancelCurrentResponse()
        val gen = ++captureGeneration
        renderWhisper()
        // §4 — Suspend conversation loop so the wake-word releases the mic before recorder opens
        conversationController?.suspendForPtt()
        setVoiceState("🎤 À l'écoute…")
        showListeningUi(true)
        val begin = {
            recorder.stopAndThen {
                runOnUiThread startCapture@{
                    if (gen != captureGeneration || !foreground ||
                        whisperController.state.phase != WhisperPhase.RECORDING) return@startCapture
                    // Le push-to-talk suit le même chemin progressif que le mode mains
                    // libres : l'antenne peut déjà transcrire pendant la prise, et le
                    // repli HTTP garde la prise complète si le WebSocket échoue.
                    val capture = runCatching {
                        streaming.begin { partial ->
                            runOnUiThread {
                                if (gen != captureGeneration || !foreground ||
                                    whisperController.state.phase != WhisperPhase.RECORDING) return@runOnUiThread
                                findViewById<TextView>(R.id.liveTranscript).apply {
                                    text = partial
                                    visibility = if (partial.isBlank()) View.GONE else View.VISIBLE
                                }
                            }
                        }
                    }.getOrNull()
                    streamCapture = capture
                    recorder.start(onLevel = { updateAudioLevel(it) },
                        onChunk = { capture?.offer(it) }) { result ->
                        runOnUiThread {
                            if (gen == captureGeneration && foreground) handleCapturedAudio(result)
                        }
                    }
                }
            }
        }
        wakeWordEngine?.stopAndThen(begin) ?: begin()
    }

    private fun stopPtt() {
        if (whisperController.state.phase == WhisperPhase.RECORDING) {
            // Covers releasing the button before the previous microphone has closed.
            recorder.stopAndThen {
                runOnUiThread {
                    if (whisperController.state.phase == WhisperPhase.RECORDING) {
                        ++captureGeneration
                        streamCapture?.cancel()
                        streamCapture = null
                        whisperController.onPttReleased(0)
                        if (preferencesController.state.conversationMode) conversationController?.resumeAfterPtt()
                        updateInterruptButton(ConversationController.State.IDLE_WAKE)
                    }
                }
            }
        }
    }

    private fun handleCapturedAudio(result: Result<ShortArray>) {
        val capture = streamCapture
        streamCapture = null
        showListeningUi(false)
        result.onFailure {
            capture?.cancel()
            whisperController.onTranscriptionFailed(it.message ?: "Erreur microphone")
            renderWhisper()
            setVoiceState("")
            // §4 — Resume conversation loop even on capture error
            if (preferencesController.state.conversationMode) {
                conversationController?.resumeAfterPtt()
            }
            return
        }
        val samples = result.getOrThrow()
        if (!whisperController.onPttReleased(PcmAudio.durationMs(samples))) {
            capture?.cancel()
            renderWhisper()
            setVoiceState("")
            // §4 — Resume conversation loop when PTT press was too short to register
            if (preferencesController.state.conversationMode) {
                conversationController?.resumeAfterPtt()
            }
            return
        }
        renderWhisper()
        setVoiceState("📝 Transcription…")
        val gen = captureGeneration
        val onTranscribed: (Result<WhisperResult>) -> Unit = { transcription ->
            runOnUiThread {
                if (gen != captureGeneration || !foreground) return@runOnUiThread
                transcription.onSuccess { output ->
                    whisperController.onTranscriptionComplete(output.text, output.elapsedMs, memoryMb())
                    renderWhisper()
                    addUserBubble(output.text)
                    setVoiceState("💭 Pepper réfléchit…")
                    // §4 — After Pepper speaks, if conversation mode is on, open the mic for the
                    // reply (continuous follow-up loop) instead of waiting for the wake-word again.
                    respondWithControlPlane(output.text) {
                        setVoiceState("")
                        if (preferencesController.state.conversationMode) {
                            conversationController?.finishPttTurn(CONVERSATION_FOLLOWUP_TIMEOUT_MS)
                        }
                    }
                }.onFailure {
                    whisperController.onTranscriptionFailed(it.message ?: "Échec de transcription")
                    renderWhisper()
                    setVoiceState("")
                    // §4 — Also resume on transcription failure
                    if (preferencesController.state.conversationMode) {
                        conversationController?.resumeAfterPtt()
                    }
                }
            }
        }
        if (capture == null) brain.transcribe(samples, onTranscribed)
        else capture.finish(samples, onTranscribed)
    }

    /**
     * Send [transcript] to the control plane (LLM + TTS).
     * [onSpoken] is invoked after Pepper finishes speaking (or on error).
     * Used by both PTT (onSpoken = resume conversation if mode is on) and ConversationHost.
     */
    private fun respondWithControlPlane(transcript: String, onSpoken: () -> Unit = {}) {
        conversationController?.acceptTranscript(transcript)
        // Invalidate hospitality requests that were issued before this user turn.
        responseGen++
        val speech = speechGateway ?: run {
            voiceError = "En attente de connexion au robot."
            conversationController?.cancelTurn()
            updateInterruptButton(ConversationController.State.IDLE_WAKE)
            return
        }
        if (!brainSettingsStore.load().isComplete) {
            runOnUiThread { addPepperBubble("⚠ Cerveau non appairé — page Cerveau") }
            onSpoken()
            return
        }
        val gen = responseGen   // snapshot; if the user cancels, responseGen changes and we bail
        val uiProvider = object : LlmProvider {
            override fun respond(prompt: String, onComplete: (Result<String>) -> Unit) {
                brain.respond(prompt) { result -> runOnUiThread { onComplete(result) } }
            }
        }
        VoiceLoop(uiProvider, speech).respond(
            transcript,
            onActions = { actions -> runOnUiThread { if (gen == responseGen) actions.forEach(::runAssistantAction) } },
            isCancelled = { gen != responseGen || !foreground },
            onSpeech = { text ->
                if (gen == responseGen && foreground) {
                    addPepperBubble(text)
                    conversationController?.onSpeechStarted()
                    updateInterruptButton(ConversationController.State.SPEAKING)
                }
            }
        ) { result ->
            runOnUiThread {
                if (gen != responseGen || !foreground) return@runOnUiThread
                if (result.isFailure) {
                    voiceError = "La réponse n’a pas abouti. Vous pouvez réessayer."
                    addPepperBubble(voiceError!!)
                    conversationController?.cancelTurn()
                    updateInterruptButton(ConversationController.State.IDLE_WAKE)
                } else onSpoken()
            }
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // Écran d'accueil
    // ────────────────────────────────────────────────────────────────────────

    /** Vide l'écran de scène — image d'accueil comprise — sans relancer le délai. */
    private fun clearOverlay() {
        imageCarousel.cancel()   // son `clear` rend déjà l'écran à l'interface
        idleImages.markVisible(false)
    }

    /**
     * Retire la seule image d'accueil. Distinct de [clearOverlay] : pendant un
     * échange, une image demandée par Pepper occupe légitimement le même écran et ne
     * doit pas partir avec elle.
     */
    private fun hideIdleImage() {
        if (!idleImages.visible) return
        idleImages.markVisible(false)
        sceneController.cancel()
    }

    /** L'écran vient d'être touché : on le rend, et l'accueil attend son délai. */
    private fun dismissOverlay() {
        clearOverlay()
        idleImages.dismiss(SystemClock.elapsedRealtime())
    }

    /**
     * Un battement : demande au cerveau quelle image montrer, puis pose ou retire
     * celle du moment. Tout passe par ici plutôt que par des réveils dispersés — un
     * seul endroit décide de ce qu'il y a à l'écran quand personne ne parle.
     */
    private fun refreshIdleImage() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastIdlePollMs >= IDLE_POLL_MS && brainSettingsStore.load().isComplete) {
            lastIdlePollMs = now
            brain.idleImage { result ->
                // Cerveau injoignable : on garde la dernière image connue. Rendre
                // brusquement la tablette à son interface devant un hall serait pire
                // que d'afficher une image d'hier une minute de trop.
                val image = result.getOrElse {
                    android.util.Log.d("BrutusIdle", "image d'accueil indisponible", it)
                    return@idleImage
                }
                runOnUiThread {
                    if (idleImages.setImage(image) && idleImages.visible) {
                        // L'image a changé sous nos yeux : on retire l'ancienne, le
                        // battement suivant posera la nouvelle.
                        hideIdleImage()
                    }
                }
            }
        }

        // Quelqu'un devant le robot compte comme de l'activité : tant qu'une personne
        // est là, même silencieuse, ni l'image d'accueil ni le retour face à l'entrée
        // ne doivent lui passer sous le nez. Le hall doit d'abord se vider.
        if (engagementListener?.humanPresent == true) idleImages.noteActivity(now)
        val conversationIdle = currentConvState == ConversationController.State.IDLE_WAKE &&
            conversationController?.isBusy() != true
        if (idleImages.consumeReadyForNextVisitor(now, conversationIdle)) readyForNextVisitor()
        val wanted = idleImages.shouldShow(now, conversationIdle, imageCarousel.isActive())
        if (wanted && !idleImages.visible) {
            idleImages.markVisible(true)
            sceneRenderer.showIdleImage(idleImages.image.url) { shown ->
                idleImages.markVisible(shown)
                // Téléchargement raté : on repousse d'un délai complet plutôt que de
                // réessayer toutes les cinq secondes derrière un cerveau éteint.
                if (!shown) idleImages.noteActivity(SystemClock.elapsedRealtime())
            }
        } else if (!wanted && idleImages.visible) {
            clearOverlay()
        }
    }

    /**
     * Le hall est resté calme : on clôt l'échange précédent et on redevient
     * disponible. Sans cela, Pepper reste bloqué sur quelqu'un qui est déjà parti —
     * l'accueil spontané ne repart pas, et un « au revoir » gardait le mot d'éveil
     * obligatoire pour le visiteur suivant.
     *
     * On ne coupe pas la voix : le micro doit rester armé pour la personne d'après.
     */
    private fun readyForNextVisitor() {
        conversationHistory.reset()
        engagedThisConversation = false
        conversationActiveUntilMs = 0L
        voiceError = null
        conversationController?.releaseExplicitWake()
        clearChatUi()
        // Le socle a suivi les visiteurs du regard : sans ce retour, la personne
        // suivante arriverait dans le dos de Pepper.
        if (preferencesController.state.returnHome) homePosition?.returnHome()
        android.util.Log.i("BrutusIdle", "hall calme : prêt pour un nouveau visiteur")
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            idleImages.noteActivity(SystemClock.elapsedRealtime())
        }
        return super.dispatchTouchEvent(event)
    }

    private fun runAssistantAction(action: AssistantAction) {
        android.util.Log.d("BrutusScene", "runAssistantAction: $action")
        when (action) {
            is PointRightAction -> actionCoordinator?.run(BoundAction.POINT_RIGHT)
            is PointLeftAction  -> actionCoordinator?.run(BoundAction.POINT_LEFT)
            is TurnAroundAction -> actionCoordinator?.run(BoundAction.TURN_AROUND)
            is DisplayImageAction -> showSearchedImage(action)
            else -> {
                clearOverlay()
                sceneController.run(action)
            }
        }
    }

    /**
     * Prononce l'accroche d'accueil et ouvre le micro dans la foulée.
     *
     * [actions] porte le geste choisi par le cerveau quand l'accroche nomme un lieu
     * qu'on sait montrer. Il part en même temps que la parole, pas après : montrer la
     * porte une fois la phrase finie n'aurait plus de sens pour le visiteur.
     */
    private fun speakGreeting(greeting: String, actions: List<AssistantAction> = emptyList()) = runOnUiThread {
        if (!foreground || !preferencesController.state.conversationMode ||
            conversationController?.awaitingExplicitWake == true ||
            conversationController?.isBusy() == true) return@runOnUiThread
        val gen = responseGen
        idleImages.noteActivity(SystemClock.elapsedRealtime())
        hideIdleImage()
        addPepperBubble(greeting)
        // Suspend the wake-word while Pepper speaks the greeting so it doesn't
        // hear itself (no self-trigger). Open the mic only once the greeting ends.
        conversationController?.suspendForPtt()
        updateInterruptButton(ConversationController.State.SPEAKING)
        actions.forEach { action ->
            when (action) {
                PointRightAction -> actionCoordinator?.run(BoundAction.POINT_RIGHT)
                PointLeftAction -> actionCoordinator?.run(BoundAction.POINT_LEFT)
                else -> { /* l'accroche ne porte que des pointages */ }
            }
        }
        speechGateway?.speak(greeting) { speakResult ->
            if (gen != responseGen || !foreground || !preferencesController.state.conversationMode) return@speak
            if (speakResult.isSuccess) {
                conversationController?.triggerDirectListening(10_000L, automatic = true)
            } else {
                conversationController?.resumeAfterPtt()
            }
        }
    }

    /**
     * « Montre-moi X » : le cerveau résout l'image pendant que Pepper parle, donc la
     * recherche ne se voit pas. Un échec reste silencieux à l'écran — la réponse
     * parlée a déjà eu lieu, un bandeau d'erreur devant un visiteur n'apporte rien.
     */
    private fun showSearchedImage(action: DisplayImageAction) {
        imageCarousel.enqueue(action)
    }

    // ────────────────────────────────────────────────────────────────────────
    // Reset conversation
    // ────────────────────────────────────────────────────────────────────────

    private fun resetConversation() {
        haltVoice()
        conversationHistory.reset()
        engagedThisConversation = false
        voiceError = null
        clearChatUi()
        applyConversationMode(preferencesController.state.conversationMode)
        updateInterruptButton(ConversationController.State.IDLE_WAKE)
    }

    // ────────────────────────────────────────────────────────────────────────
    // Helpers
    // ────────────────────────────────────────────────────────────────────────

    private fun centeredAxis(event: MotionEvent, device: InputDevice, axis: Int): Float {
        val range = device.getMotionRange(axis, event.source) ?: return 0f
        return inputMapper.centeredAxis(event.getAxisValue(axis), range.flat)
    }

    private fun memoryMb(): Int = (Debug.getPss() / 1024L).coerceAtLeast(0L).toInt()

    companion object {
        /**
         * Accueil ordinaire, hors mode hospitalité — et repli quand le cerveau ne
         * répond pas : un visiteur ne doit jamais rester sans bonjour.
         */
        private val DEFAULT_GREETINGS = listOf(
            "Bonjour, je suis Pepper. Comment puis-je vous aider ?",
            "Salut ! Je m'appelle Pepper. Que puis-je faire pour vous ?",
            "Bonjour ! Je suis Pepper. En quoi puis-je vous être utile aujourd'hui ?",
            "Bonjour, ravi de vous rencontrer. Je suis Pepper. Comment puis-je vous aider ?",
            "Coucou ! Je suis le robot Pepper. Comment puis-je vous aider ?"
        )

        /**
         * Plafond d'une prise de parole. C'est un filet, pas la façon normale de
         * terminer un tour : le portier conclut dès que la personne a fini.
         *
         * Le choix se joue entre deux inconforts. Trop bas, on tronque quelqu'un qui a
         * mis du temps à se lancer — c'était le défaut d'avant, six secondes servant à
         * la fois de délai d'attente et de plafond. Trop haut, un échec de détection se
         * paie en secondes de silence gênant devant un visiteur.
         */
        private const val MAX_UTTERANCE_MS = 15_000L

        private const val MICROPHONE_REQUEST = 41
        /** §4 — Onset timeout for conversation mode: give up after 6s without speech. */
        private const val CONVERSATION_ONSET_TIMEOUT_MS = 10_000L
        /** §4 — After Pepper speaks, how long to keep the mic open for the reply. */
        private const val CONVERSATION_FOLLOWUP_TIMEOUT_MS = 10_000L
        /** Grace window after conversation activity during which auto-engage stays blocked. */
        private const val CONVERSATION_GRACE_MS = 20_000L

        /** Cadence du battement de l'écran d'accueil : assez fin pour ne pas se voir. */
        private const val IDLE_TICK_MS = 5_000L
        /** Intervalle entre deux demandes de l'image d'accueil au cerveau. */
        private const val IDLE_POLL_MS = 60_000L
    }
}
