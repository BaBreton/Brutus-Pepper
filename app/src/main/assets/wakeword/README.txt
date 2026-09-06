Wake-word model files (Porcupine)
==================================

Drop your Picovoice Android .ppn keyword files here.

Expected filenames (see WakeWordConfig.kt):
  - pepper_android.ppn    (keyword "Pepper")
  - ppr_android.ppn       (keyword "PPR", optional)

To generate these files:
  1. Go to https://console.picovoice.ai/
  2. Create a custom keyword for Android platform
  3. Download the .ppn file and copy it to this directory

Until at least one .ppn file is present AND WakeWordConfig.ACCESS_KEY is set,
WakeWordEngine runs as a no-op and the app operates in PTT-only mode.
