package org.example.paging;

/**
 * Types named *Signal in a fixed source order: eleven records, eleven classes, one more
 * record, then an interface, an enum and an annotation type.
 */
public class Signals {

    record AlarmSignal() {
    }

    record BeaconSignal() {
    }

    record ChimeSignal() {
    }

    record DrumSignal() {
    }

    record EchoSignal() {
    }

    record FlareSignal() {
    }

    record GongSignal() {
    }

    record HornSignal() {
    }

    record IconSignal() {
    }

    record JingleSignal() {
    }

    record KnockSignal() {
    }

    static class LampSignal {
    }

    static class MorseSignal {
    }

    static class NoteSignal {
    }

    static class OrbSignal {
    }

    static class PingSignal {
    }

    static class QuerySignal {
    }

    static class RadioSignal {
    }

    static class SirenSignal {
    }

    static class TorchSignal {
    }

    static class UrgentSignal {
    }

    static class VoiceSignal {
    }

    record WaveSignal() {
    }

    interface XraySignal {
    }

    enum YellSignal {
        LOUD
    }

    @interface ZoneSignal {
    }
}
