# Writes a 5-second 440 Hz test tone so the emulator has something to index and play.
import math, struct, wave
with wave.open("test_tone.wav", "wb") as w:
    w.setnchannels(2); w.setsampwidth(2); w.setframerate(44100)
    frames = bytearray()
    for i in range(44100 * 5):
        v = int(12000 * math.sin(2 * math.pi * 440 * i / 44100))
        frames += struct.pack("<hh", v, v)
    w.writeframes(bytes(frames))
