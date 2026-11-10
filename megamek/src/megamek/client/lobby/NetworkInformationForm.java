/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.lobby;

import java.util.List;
import java.util.function.Supplier;

import jakarta.annotation.Nullable;
import megamek.client.Client;
import megamek.client.ui.Messages;
import megamek.codeUtilities.StringUtility;
import megamek.common.net.NetworkAddresses;

/**
 * The addresses the Network information card shows: this computer's address on its network, the network's address on
 * the internet, and the host the game is connected to, as the Swing {@code NetworkInformationDialog} showed them.
 * Finding the public address asks a web service and can take a few seconds, so {@link #read()} is kept off the GPU and
 * Swing threads; {@link #pending()} is what shows meanwhile.
 */
public final class NetworkInformationForm {

    /** Which address a row holds. */
    public enum Kind {
        LOCAL("NetworkInformation.native.local", "NetworkInformation.native.noLocal"),
        PUBLIC("NetworkInformation.native.public", "NetworkInformation.native.noPublic"),
        HOST("NetworkInformation.native.host", "NetworkInformation.native.noHost");

        private final String labelKey;
        private final String missingKey;

        Kind(String labelKey, String missingKey) {
            this.labelKey = labelKey;
            this.missingKey = missingKey;
        }

        /** The row's field label. */
        public String label() {
            return Messages.getString(labelKey);
        }

        /** What the row shows when the address could not be found. */
        public String missing() {
            return Messages.getString(missingKey);
        }
    }

    /**
     * One address.
     *
     * @param address the address, or null when it could not be found or is still being looked up
     * @param pending whether it is still being looked up
     */
    public record Row(Kind kind, @Nullable String address, boolean pending) {
        /** What the row shows: the address, or why there is none. */
        public String text() {
            if (address != null) {
                return address;
            }
            return pending ? Messages.getString("NetworkInformation.native.looking") : kind.missing();
        }

        /** Whether the row holds an address, which it then hides until shown. */
        public boolean found() {
            return address != null;
        }
    }

    private final Supplier<String> local;
    private final Supplier<String> publicAddress;
    private final @Nullable String host;

    /**
     * @param local         finds this computer's address on its network, or null
     * @param publicAddress finds the network's address on the internet, or null; may take a few seconds
     * @param host          the host the game is connected to, or null
     */
    public NetworkInformationForm(Supplier<String> local, Supplier<String> publicAddress, @Nullable String host) {
        this.local = local;
        this.publicAddress = publicAddress;
        this.host = host;
    }

    /** The addresses of {@code client}'s computer and the host it is connected to. */
    public static NetworkInformationForm of(Client client) {
        return new NetworkInformationForm(NetworkAddresses::localAddress, NetworkAddresses::publicAddress,
              client.getHost());
    }

    /** The rows before anything is looked up: the host is known, the two addresses are being looked up. */
    public List<Row> pending() {
        return List.of(new Row(Kind.LOCAL, null, true), new Row(Kind.PUBLIC, null, true), hostRow());
    }

    /** Looks the addresses up; can take a few seconds, so never on the GPU or Swing thread. */
    public List<Row> read() {
        return List.of(new Row(Kind.LOCAL, clean(local.get()), false),
              new Row(Kind.PUBLIC, clean(publicAddress.get()), false), hostRow());
    }

    private Row hostRow() {
        return new Row(Kind.HOST, clean(host), false);
    }

    private static @Nullable String clean(@Nullable String address) {
        return StringUtility.isNullOrBlank(address) ? null : address.trim();
    }
}
