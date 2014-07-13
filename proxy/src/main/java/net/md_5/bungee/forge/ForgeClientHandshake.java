package net.md_5.bungee.forge;

import lombok.RequiredArgsConstructor;
import net.md_5.bungee.UserConnection;
import net.md_5.bungee.protocol.ProtocolConstants;
import net.md_5.bungee.protocol.packet.LoginSuccess;
import net.md_5.bungee.protocol.packet.PluginMessage;

/**
 * Handles the Forge Client handshake procedure.
 */
@RequiredArgsConstructor
public class ForgeClientHandshake
{
    private final UserConnection con;
    
    private ForgeClientHandshakeState state = ForgeClientHandshakeState.START;
    
    private PluginMessage serverModList;
    private PluginMessage serverIdList;
    
    /**
     * Handles the Forge packet.
     * @param message The Forge Handshake packet to handle.
     */
    public void handle(PluginMessage message) throws IllegalArgumentException {
        if (!message.getTag().equalsIgnoreCase( "FML|HS" )) {
            throw new IllegalArgumentException("Expecting a Forge Handshake packet.");
        }
        
        state = state.handle( message, con );
        
        if (state == ForgeClientHandshakeState.SENDMODLIST && serverModList != null) {
            setServerModList(serverModList);

            // Null the server mod list now, we don't need to keep it in memory.
            serverModList = null;
        } else if (state == ForgeClientHandshakeState.COMPLETEHANDSHAKE && serverIdList != null) {
            setServerIdList(serverIdList);

            // Null the server mod list now, we don't need to keep it in memory.
            serverIdList = null;
        }
    }

    /**
     * Starts a Forge handshake.
     */
    public void startHandshake() {
        if (state == ForgeClientHandshakeState.START) {
            // For the START state, it's already part of the call. No need to provide the
            // plugin message here.
            state = state.send( null, con);
        }
    }

    /**
     * Sends a LoginSuccess packet to the Forge client, to reset the handshake state.
     */
    public void resetHandshake() {
        serverModList = null;
        serverIdList = null;
        state = ForgeClientHandshakeState.START;

        // Send a LoginSuccess packet to reset the handshake.
        if (con.getPendingConnection().getVersion() >= ProtocolConstants.MINECRAFT_1_7_6) {
            con.unsafe().sendPacket(new LoginSuccess(con.getUniqueId().toString(), con.getName())); // With dashes in between
        } else {
            con.unsafe().sendPacket(new LoginSuccess(con.getUUID(), con.getName())); // Without dashes, for older clients.
        }
        
        // Now start the handshake again
        startHandshake();
    }

    /**
     * Sends the server mod list to the client, or stores it for sending later.
     * 
     * @param modList The {@link PluginMessage} to send to the client containing the mod list.
     * @throws IllegalArgumentException Thrown if the {@link PluginMessage} was not as expected.
     */
    public void setServerModList(PluginMessage modList) throws IllegalArgumentException {
        if (!modList.getTag().equalsIgnoreCase( "FML|HS" ) || modList.getData()[0] != 2) {
            throw new IllegalArgumentException("modList");
        }
        
        if (state == ForgeClientHandshakeState.SENDMODLIST) {
            // Directly send it.
            state = state.send( modList, con );
        } else {
            // Store it for use later.
            this.serverModList = modList;
        }
    }

    /**
     * Sends the server ID list to the client, or stores it for sending later.
     * 
     * @param idList The {@link PluginMessage} to send to the client containing the ID list.
     * @throws IllegalArgumentException Thrown if the {@link PluginMessage} was not as expected.
     */
    public void setServerIdList(PluginMessage idList) throws IllegalArgumentException {
        if (!idList.getTag().equalsIgnoreCase( "FML|HS" ) || idList.getData()[0] != 3) {
            throw new IllegalArgumentException("idList");
        }
        
        if (state == ForgeClientHandshakeState.COMPLETEHANDSHAKE) {
            // Directly send it.
            state = state.send( idList, con );
        } else {
            // Store it for use later.
            this.serverIdList = idList;
        }
    }
    
    /**
     * Returns whether the handshake is complete.
     * @return <code>true</code> if the handshake has been completed.
     */
    public boolean isHandshakeComplete() {
        return state == ForgeClientHandshakeState.DONE;
    }
}
