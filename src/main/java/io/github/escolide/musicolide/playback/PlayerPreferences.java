package io.github.escolide.musicolide.playback;

public final class PlayerPreferences {
    private int volume;
    private boolean muted;

    public PlayerPreferences(int volume, boolean muted) {
        this.volume = Math.max(0, Math.min(100, volume));
        this.muted = muted;
    }
    public int volume() { return volume; }
    public void volume(int volume) { this.volume = Math.max(0, Math.min(100, volume)); }
    public boolean muted() { return muted; }
    public void muted(boolean muted) { this.muted = muted; }
}
