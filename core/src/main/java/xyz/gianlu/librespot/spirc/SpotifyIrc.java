package xyz.gianlu.librespot.spirc;

import com.google.protobuf.InvalidProtocolBufferException;
import org.apache.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.gianlu.librespot.Version;
import xyz.gianlu.librespot.core.Session;
import xyz.gianlu.librespot.mercury.MercuryClient;
import xyz.gianlu.librespot.mercury.RawMercuryRequest;
import xyz.gianlu.librespot.mercury.SubListener;
import xyz.gianlu.librespot.player.PlayerRunner;
import xyz.gianlu.librespot.common.proto.Spirc;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @author Gianlu
 */
public class SpotifyIrc {
    private static final Logger LOGGER = Logger.getLogger(SpotifyIrc.class);
    private final AtomicInteger seqHolder = new AtomicInteger(1);
    private final String uri;
    private final Session session;
    private final SpircListener internalListener;
    private final Spirc.DeviceState.Builder deviceState;

    public SpotifyIrc(@NotNull Session session) throws IOException, IrcException, MercuryClient.PubSubException {
        this.session = session;
        this.uri = String.format("hm://remote/user/%s/", session.apWelcome().getCanonicalUsername());
        this.deviceState = initializeDeviceState();

        session.mercury().subscribe(uri, internalListener = new SpircListener());

        send(Spirc.MessageType.kMessageTypeHello);
    }

    @NotNull
    private Spirc.DeviceState.Builder initializeDeviceState() {
        return Spirc.DeviceState.newBuilder()
                .setCanPlay(true)
                .setIsActive(false)
                .setVolume(PlayerRunner.VOLUME_MAX)
                .setName(session.deviceName())
                .setSwVersion(Version.versionString())
                .addCapabilities(Spirc.Capability.newBuilder()
                        .setTyp(Spirc.CapabilityType.kCanBePlayer)
                        .addIntValue(1)
                        .build())
                .addCapabilities(Spirc.Capability.newBuilder()
                        .setTyp(Spirc.CapabilityType.kDeviceType)
                        .addIntValue(session.deviceType().val)
                        .build())
                .addCapabilities(Spirc.Capability.newBuilder()
                        .setTyp(Spirc.CapabilityType.kGaiaEqConnectId)
                        .addIntValue(1)
                        .build())
                .addCapabilities(Spirc.Capability.newBuilder()
                        .setTyp(Spirc.CapabilityType.kSupportsLogout)
                        .addIntValue(0)
                        .build())
                .addCapabilities(Spirc.Capability.newBuilder()
                        .setTyp(Spirc.CapabilityType.kIsObservable)
                        .addIntValue(1)
                        .build())
                .addCapabilities(Spirc.Capability.newBuilder()
                        .setTyp(Spirc.CapabilityType.kVolumeSteps)
                        .addIntValue(PlayerRunner.VOLUME_STEPS)
                        .build())
                .addCapabilities(Spirc.Capability.newBuilder()
                        .setTyp(Spirc.CapabilityType.kSupportedContexts)
                        .addStringValue("album")
                        .addStringValue("playlist")
                        .addStringValue("search")
                        .addStringValue("inbox")
                        .addStringValue("toplist")
                        .addStringValue("starred")
                        .addStringValue("publishedstarred")
                        .addStringValue("track")
                        .build())
                .addCapabilities(Spirc.Capability.newBuilder()
                        .setTyp(Spirc.CapabilityType.kSupportedTypes)
                        .addStringValue("audio/local")
                        .addStringValue("audio/track")
                        .addStringValue("local")
                        .addStringValue("track")
                        .build());
    }

    public synchronized void send(@NotNull Spirc.MessageType type) throws IOException, IrcException {
        send(type, Spirc.Frame.newBuilder());
    }

    public synchronized void send(@NotNull Spirc.MessageType type, @NotNull Spirc.Frame.Builder builder) throws IOException, IrcException {
        LOGGER.trace("Send frame, type: " + type);

        Spirc.Frame frame = builder
                .setVersion(1)
                .setTyp(type)
                .setSeqNr(seqHolder.getAndIncrement())
                .setIdent(session.deviceId())
                .setProtocolVersion("2.0.0")
                .setDeviceState(deviceState)
                .setStateUpdateId(System.currentTimeMillis())
                .build();

        MercuryClient.Response response = session.mercury().sendSync(RawMercuryRequest.send(uri, frame.toByteArray()));
        if (response.statusCode == 200) {
            LOGGER.trace("Frame sent successfully, type: " + type);
        } else {
            throw new IrcException(response);
        }
    }

    private void sendNotify() throws IOException, IrcException {
        sendNotify(null, null);
    }

    private void sendNotify(@Nullable String recipient, @Nullable Spirc.Frame.Builder frame) throws IOException, IrcException {
        if (frame == null) frame = Spirc.Frame.newBuilder();

        if (recipient == null) send(Spirc.MessageType.kMessageTypeNotify, frame);
        else send(Spirc.MessageType.kMessageTypeNotify, frame.addRecipient(recipient));
    }

    private void handleFrame(@NotNull Spirc.Frame frame) throws IOException, IrcException {
        switch (frame.getTyp()) {
            case kMessageTypeHello:
                sendNotify(frame.getIdent(), null);
                break;
        }
    }

    public void addListener(@NotNull FrameListener listener) {
        synchronized (internalListener.listeners) {
            internalListener.listeners.add(listener);
        }
    }

    @NotNull
    public Spirc.DeviceState.Builder deviceState() {
        return deviceState;
    }

    public void deviceStateUpdated(@NotNull Spirc.State.Builder state) {
        try {
            sendNotify(null, Spirc.Frame.newBuilder().setState(state));
        } catch (IOException | IrcException ex) {
            LOGGER.fatal("Failed notifying device state changed!", ex);
        }
    }

    public static class IrcException extends Exception {
        private IrcException(MercuryClient.Response response) {
            super(String.format("status: %d", response.statusCode));
        }
    }

    private final class SpircListener implements SubListener {
        private final Set<FrameListener> listeners = new HashSet<>();

        @Override
        public final void event(MercuryClient.@NotNull Response resp) {
            String ident = session.deviceId();

            try {
                Spirc.Frame frame = Spirc.Frame.parseFrom(resp.payload.get(0));
                if (ident.equals(frame.getIdent()) || (frame.getRecipientCount() > 0 && !frame.getRecipientList().contains(ident))) {
                    LOGGER.trace(String.format("Skipping message, not for us, ident: %s, recipients: %s", frame.getIdent(), frame.getRecipientList()));
                    return;
                }

                LOGGER.trace(String.format("Handling frame, type: %s, ident: %s", frame.getTyp(), frame.getIdent()));

                handleFrame(frame);
                for (FrameListener listener : listeners)
                    listener.frame(frame);
            } catch (InvalidProtocolBufferException ex) {
                LOGGER.fatal("Couldn't create frame!", ex);
            } catch (IOException | IrcException ex) {
                LOGGER.fatal("Failed handling frame!", ex);
            }
        }
    }
}
