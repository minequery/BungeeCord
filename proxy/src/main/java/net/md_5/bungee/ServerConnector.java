package net.md_5.bungee;

import com.google.common.base.Preconditions;
import java.util.Objects;
import java.util.Queue;
import java.util.Timer;
import java.util.TimerTask;
import lombok.Getter;
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

        // If we get here, and no Forge handshake has taken place, then we have a vanilla server
        // In this case, send the empty mod list and vanilla ID payloads to the server.
        // This is the last moment we can do this. 
        if (!serverIsForge) {
           user.sendVanillaForgeData();
        }

        if ( user.getServer() == null )
        {
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
        if(pluginMessage.getTag().equals("FML|HS"))
        {
            // If we get here, we have a FML server. Flag it up.
            serverIsForge = true;

            byte state = pluginMessage.getData()[ 0 ];
            switch ( state )
            {
                case -1:
                    // ACK
                    user.sendData( "FML|HS", pluginMessage.getData() );
                    break;
                case 0:
                    // Server hello
                    if (!user.isForgeUser()) {
                        user.setDelayedServerPacket( pluginMessage );
                        user.setDelayedServerPacketHandler( this );

                        // If we cannot identify them as a forge user, then wait a couple of seconds, as we might be waiting for the 
                        // user to complete the forge handshake. 
                        Timer timer = new Timer();
                        timer.schedule(new TimerTask() {

                            @Override
                            public void run()
                            {
                                // Kill the timer so that it does not run again.
                                Timer timer = user.getDelayedServerPacketTimeoutTimer();
                                if (timer != null) {
                                    timer.cancel();
                                }

                                user.setDelayedServerPacketTimeoutTimer( null );

                                // If this wasn't cancelled, then continue anyway.
                                if (user.isForgeUser()) {
                                    return;
                                }

                                try {
                                    // If the user is not a mod user, then throw them off.
                                    user.getPendingConnects().remove( target );
                                    ch.close();

                                    String message = bungee.getTranslation( "connect_kick" ) + target.getName() + ": " + bungee.getTranslation( "connect_kick_modded" );
                                    if ( user.getServer() == null ) {
                                        user.disconnect( message );
                                    } else {
                                        user.sendMessage( ChatColor.RED + message );
                                    }
                                }
                                finally
                                {
                                }
                            }
                        }, 2000); // TODO: Is this reasonable?
                        
                        user.setDelayedServerPacketTimeoutTimer( timer );
                    } else {
                        // Else, start the handshake. Do not send the Hello to the client, as this will cause a cast error.
                        ch.write( PacketConstants.FML_REGISTER );
                        ch.write( PacketConstants.FML_START_SERVER_HANDSHAKE );
                        ch.write( new PluginMessage( "FML|HS", user.getFmlModData(), true ) );
                    }

                    break;
                case 2:
                    // ModList
                    user.sendData( "FML|HS", pluginMessage.getData() );
                    ch.write( new PluginMessage( "FML|HS", new byte[]{ -1, 2 }, true ) );
                    break;
                case 3:
                    // IdList
                    user.sendData( "FML|HS", pluginMessage.getData() );
                    ch.write( new PluginMessage( "FML|HS", new byte[]{ -1, 2 }, true ) );
                    break;
            }
            throw CancelSendSignal.INSTANCE;
        }
    }

    @Override
    public String toString()
    {
        return "[" + user.getName() + "] <-> ServerConnector [" + target.getName() + "]";
    }
}
