package net.md_5.bungee;

import com.google.common.io.BaseEncoding;
import net.md_5.bungee.protocol.packet.ClientStatus;
import net.md_5.bungee.protocol.packet.PluginMessage;
import net.md_5.bungee.protocol.packet.Respawn;

public class PacketConstants
{
    private static byte[] vanillaBlocks17 = null;
    
    public static final Respawn DIM1_SWITCH = new Respawn( (byte) 1, (byte) 0, (byte) 0, "default" );
    public static final Respawn DIM2_SWITCH = new Respawn( (byte) -1, (byte) 0, (byte) 0, "default" );
    public static final ClientStatus CLIENT_LOGIN = new ClientStatus( (byte) 0 );
    public static final PluginMessage FORGE_MOD_REQUEST = new PluginMessage( "FML", new byte[]
    {
        0, 0, 0, 0, 0, 2
    }, false );
    public static final PluginMessage I_AM_BUNGEE = new PluginMessage( "BungeeCord", new byte[ 0 ], false );

    // Forge
    public static final PluginMessage FML_REGISTER = new PluginMessage( "REGISTER", "FML|HS".getBytes(), false );
    public static final PluginMessage FML_START_CLIENT_HANDSHAKE = new PluginMessage( "FML|HS", new byte[] { 0, 1 }, false );
    public static final PluginMessage FML_START_SERVER_HANDSHAKE = new PluginMessage( "FML|HS", new byte[] { 1, 1 }, false );
    public static final PluginMessage FML_EMPTY_MOD_LIST = new PluginMessage( "FML|HS", new byte[] { 2, 0 }, false );
    
    // Vanilla blocks. Obtained though packet sniffing. See the ForgeConstants class
    public static final PluginMessage FML_DEFAULT_IDS_17 = new PluginMessage( "FML|HS", getVanillaBlocks17(), true);
    
    /**
     * Gets the Vanilla Blocks ID list for Minecraft 1.7 from the ForgeConstants class,
     * caches the byte form, and returns it.
     * @return The byte form of the ID list to return.
     */
    private static byte[] getVanillaBlocks17() {
        if (vanillaBlocks17 != null) {
            return vanillaBlocks17;
        }
        
        // Construct it once and store the bytes.
        vanillaBlocks17 = BaseEncoding.base64().decode( ForgeConstants.base64encBlocks17 );
        return vanillaBlocks17;
    }    
}
