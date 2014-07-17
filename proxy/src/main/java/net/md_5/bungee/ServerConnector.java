package net.md_5.bungee;

import com.google.common.base.Preconditions;
import java.util.Objects;
import java.util.Queue;
import java.util.Timer;
import java.util.TimerTask;
import lombok.RequiredArgsConstructor;
import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.config.ServerInfo;
import net.md_5.bungee.api.event.ServerConnectedEvent;
import net.md_5.bungee.api.event.ServerKickEvent;
import net.md_5.bungee.api.event.ServerSwitchEvent;
import net.md_5.bungee.api.score.Objective;
import net.md_5.bungee.api.score.Scoreboard;
import net.md_5.bungee.api.score.Team;
import net.md_5.bungee.chat.ComponentSerializer;
import net.md_5.bungee.connection.CancelSendSignal;
import net.md_5.bungee.connection.DownstreamBridge;
import net.md_5.bungee.connection.LoginResult;
import net.md_5.bungee.forge.ForgeConstants;
import net.md_5.bungee.forge.ForgeServer;
import net.md_5.bungee.netty.ChannelWrapper;
import net.md_5.bungee.netty.HandlerBoss;
import net.md_5.bungee.netty.PacketHandler;
import net.md_5.bungee.protocol.DefinedPacket;
import net.md_5.bungee.protocol.MinecraftOutput;
import net.md_5.bungee.protocol.Protocol;
import net.md_5.bungee.protocol.packet.EncryptionRequest;
import net.md_5.bungee.protocol.packet.Handshake;
import net.md_5.bungee.protocol.packet.Kick;
import net.md_5.bungee.protocol.packet.Login;
import net.md_5.bungee.protocol.packet.LoginSuccess;
import net.md_5.bungee.protocol.packet.PluginMessage;
import net.md_5.bungee.protocol.packet.Respawn;
import net.md_5.bungee.protocol.packet.ScoreboardObjective;

@RequiredArgsConstructor
public class ServerConnector extends PacketHandler
{

    private final ProxyServer bungee;
    private ChannelWrapper ch;
    private final UserConnection user;
    private final BungeeServerInfo target;
    private State thisState = State.LOGIN_SUCCESS;
    
    private ForgeServer handshakeHandler;
    private PluginMessage modList = ForgeConstants.FML_EMPTY_MOD_LIST;
    private PluginMessage idList = ForgeConstants.FML_DEFAULT_IDS_17;

    /**
     * A flag that indicates whether the server that is being connected to has
     * started a FML handshake. Used to determine whether to send an empty mod list
     * and ID list.
     *
     * We start as false, as we have no idea if we are connecting to the FML server.
     */
    private boolean serverIsForge = false;

    private enum State
    {
        LOGIN_SUCCESS, ENCRYPT_RESPONSE, LOGIN, FINISHED;
    }

    @Override
    public void exception(Throwable t) throws Exception
    {
        String message = "Exception Connecting:" + Util.exception( t );
        if ( user.getServer() == null )
        {
            user.disconnect( message );
        } else
        {
            user.sendMessage( ChatColor.RED + message );
        }
    }

    @Override
    public void connected(ChannelWrapper channel) throws Exception
    {
        this.ch = channel;

        // Enable the forge handshake handler
        this.handshakeHandler = new ForgeServer(user, ch, target, bungee);

        Handshake originalHandshake = user.getPendingConnection().getHandshake();
        Handshake copiedHandshake = new Handshake( originalHandshake.getProtocolVersion(), originalHandshake.getHost(), originalHandshake.getPort(), 2 );

        if ( BungeeCord.getInstance().config.isIpFoward() )
        {
            String newHost = copiedHandshake.getHost() + "\00" + user.getAddress().getHostString() + "\00" + user.getUUID();

            LoginResult profile = user.getPendingConnection().getLoginProfile();
            if ( profile != null && profile.getProperties() != null && profile.getProperties().length > 0 )
            {
                newHost += "\00" + BungeeCord.getInstance().gson.toJson( profile.getProperties() );
            }
            copiedHandshake.setHost( newHost );
        }
        channel.write( copiedHandshake );

        channel.setProtocol( Protocol.LOGIN );
        channel.write( user.getPendingConnection().getLoginRequest() );
    }

    @Override
    public void disconnected(ChannelWrapper channel) throws Exception
    {
        user.getPendingConnects().remove( target );
    }

    @Override
    public void handle(LoginSuccess loginSuccess) throws Exception
    {
        Preconditions.checkState( thisState == State.LOGIN_SUCCESS, "Not expecting LOGIN_SUCCESS" );
        ch.setProtocol( Protocol.GAME );
        thisState = State.LOGIN;

        throw CancelSendSignal.INSTANCE;
    }

    @Override
    public void handle(Login login) throws Exception
    {
        Preconditions.checkState( thisState == State.LOGIN, "Not expecting LOGIN" );

        ServerConnection server = new ServerConnection( ch, target );
        ServerConnectedEvent event = new ServerConnectedEvent( user, server );
        bungee.getPluginManager().callEvent( event );

        ch.write( BungeeCord.getInstance().registerChannels() );
        Queue<DefinedPacket> packetQueue = target.getPacketQueue();
        synchronized ( packetQueue )
        {
            while ( !packetQueue.isEmpty() )
            {
                ch.write( packetQueue.poll() );
            }
        }

        for ( PluginMessage message : user.getPendingConnection().getRegisterMessages() )
        {
            ch.write( message );
        }

        if ( user.getSettings() != null )
        {
            ch.write( user.getSettings() );
        }

        if ( user.getServer() == null )
        {
            if (!serverIsForge && !user.getForgeClientData().isHandshakeComplete()) {
                // Set the mod and ID list data for the forge handshake. If we are
                // logging onto a Vanilla server, we can't assume that the user isn't Forge,
                // and that the handshake will have completed by now, so set it for everyone.
                //
                // If the user is forge, then we have to do the handshake much earlier. See the 
                // plugin message handler.
                user.getForgeClientData().setServerModList( modList );
                user.getForgeClientData().setServerIdList( idList );
            }
                    
            // Once again, first connection
            user.setClientEntityId( login.getEntityId() );
            user.setServerEntityId( login.getEntityId() );

            // Set tab list size, this sucks balls, TODO: what shall we do about packet mutability
            Login modLogin = new Login( login.getEntityId(), login.getGameMode(), (byte) login.getDimension(), login.getDifficulty(),
                    (byte) user.getPendingConnection().getListener().getTabListSize(), login.getLevelType() );

            user.unsafe().sendPacket( modLogin );

            MinecraftOutput out = new MinecraftOutput();
            out.writeStringUTF8WithoutLengthHeaderBecauseDinnerboneStuffedUpTheMCBrandPacket( ProxyServer.getInstance().getName() + " (" + ProxyServer.getInstance().getVersion() + ")" );
            user.unsafe().sendPacket( new PluginMessage( "MC|Brand", out.toArray(), serverIsForge ) );
        } else
        {
            // If we already have a completed handshake, we need to reset the handshake now. We then set the
            // vanilla forge data. This should be handled automatically by the handshake handler.
            if (user.getForgeClientData().isHandshakeComplete()) {
                user.getForgeClientData().resetHandshake();
                
                // Set the mod and ID list data for the handshake. By this point, we know that the user is a Forge user,
                // so we just set it in these cases.
                user.getForgeClientData().setServerModList( modList );
                user.getForgeClientData().setServerIdList(idList );
            }
            
            user.getTabList().onServerChange();

            Scoreboard serverScoreboard = user.getServerSentScoreboard();
            for ( Objective objective : serverScoreboard.getObjectives() )
            {
                user.unsafe().sendPacket( new ScoreboardObjective( objective.getName(), objective.getValue(), "integer", (byte) 1 ) ); // TODO:
            }
            for ( Team team : serverScoreboard.getTeams() )
            {
                user.unsafe().sendPacket( new net.md_5.bungee.protocol.packet.Team( team.getName() ) );
            }
            serverScoreboard.clear();

            user.sendDimensionSwitch();

            user.setServerEntityId( login.getEntityId() );
            user.unsafe().sendPacket( new Respawn( login.getDimension(), login.getDifficulty(), login.getGameMode(), login.getLevelType() ) );

            // Remove from old servers
            user.getServer().setObsolete( true );
            user.getServer().disconnect( "Quitting" );
        }

        // TODO: Fix this?
        if ( !user.isActive() )
        {
            server.disconnect( "Quitting" );
            // Silly server admins see stack trace and die
            bungee.getLogger().warning( "No client connected for pending server!" );
            return;
        }

        // Add to new server
        // TODO: Move this to the connected() method of DownstreamBridge
        target.addPlayer( user );
        user.getPendingConnects().remove( target );
        user.setDimensionChange( false );

        user.setServer( server );
        ch.getHandle().pipeline().get( HandlerBoss.class ).setHandler( new DownstreamBridge( bungee, user, server ) );

        bungee.getPluginManager().callEvent( new ServerSwitchEvent( user ) );

        thisState = State.FINISHED;

        throw CancelSendSignal.INSTANCE;
    }

    @Override
    public void handle(EncryptionRequest encryptionRequest) throws Exception
    {
        throw new RuntimeException( "Server is online mode!" );
    }

    @Override
    public void handle(Kick kick) throws Exception
    {
        ServerInfo def = bungee.getServerInfo( user.getPendingConnection().getListener().getFallbackServer() );
        if ( Objects.equals( target, def ) )
        {
            def = null;
        }
        ServerKickEvent event = bungee.getPluginManager().callEvent( new ServerKickEvent( user, target, ComponentSerializer.parse( kick.getMessage() ), def, ServerKickEvent.State.CONNECTING ) );
        if ( event.isCancelled() && event.getCancelServer() != null )
        {
            user.connect( event.getCancelServer() );
            throw CancelSendSignal.INSTANCE;
        }

        String message = bungee.getTranslation( "connect_kick" ) + target.getName() + ": " + event.getKickReason();
        if ( user.isDimensionChange() )
        {
            user.disconnect( message );
        } else
        {
            user.sendMessage( message );
        }

        throw CancelSendSignal.INSTANCE;
    }

    @Override
    public void handle(PluginMessage pluginMessage) throws Exception
    {
        if(pluginMessage.getTag().equals(ForgeConstants.FORGE_HANDSHAKE_TAG))
        {
            // If we get here, we have a FML server. Flag it up.
            serverIsForge = true;
            handshakeHandler.handle( pluginMessage );
            throw CancelSendSignal.INSTANCE;
        }
    }

    @Override
    public String toString()
    {
        return "[" + user.getName() + "] <-> ServerConnector [" + target.getName() + "]";
    }
}
