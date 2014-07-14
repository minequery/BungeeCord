package net.md_5.bungee.forge;

import net.md_5.bungee.UserConnection;
import net.md_5.bungee.netty.ChannelWrapper;
import net.md_5.bungee.protocol.packet.PluginMessage;

public enum ForgeServerHandshakeState implements IForgeServerPacketHandler<ForgeServerHandshakeState> {
    START {
        @Override
        public ForgeServerHandshakeState handle(PluginMessage message, UserConnection con, ChannelWrapper ch)
        {
            // If the user is a Forge user already, return the state from the
            // PENDINGUSER send method.
            if (con.isForgeUser()) {
                return PENDINGUSER.send( new PluginMessage ( ForgeConstants.forgeTag, con.getFmlModData(), true), con, ch );
            }

            // Otherwise, we have to wait for the user to be ready. 
            return PENDINGUSER;
        }

        @Override
        public ForgeServerHandshakeState send(PluginMessage message, UserConnection con, ChannelWrapper ch)
        {
            return this;
        }
        
    },
    PENDINGUSER {

        @Override
        public ForgeServerHandshakeState handle(PluginMessage message, UserConnection con, ChannelWrapper ch)
        {
            return this;
        }

        @Override
        public ForgeServerHandshakeState send(PluginMessage message, UserConnection con, ChannelWrapper ch)
        {
            // Send custom channel registration. Send Hello. Send Server Mod List.
            ch.write( ForgeConstants.FML_REGISTER );
            ch.write( ForgeConstants.FML_START_SERVER_HANDSHAKE );
            ch.write( message );

            return WAITINGFORSERVERDATA;
        }
    },
    WAITINGFORSERVERDATA {

        @Override
        public ForgeServerHandshakeState handle(PluginMessage message, UserConnection con, ChannelWrapper ch)
        {
            // Mod List
            if (message.getData()[0] == 2) {
                // Send ACK
                ch.write( ForgeConstants.FML_ACK );
                con.getForgeHandshakeHandler().setServerModList( message );
                return WAITFORIDLIST;
            }

            return this;
        }

        @Override
        public ForgeServerHandshakeState send(PluginMessage message, UserConnection con, ChannelWrapper ch)
        {
            return this;
        }
    },
    WAITFORIDLIST {

        @Override
        public ForgeServerHandshakeState handle(PluginMessage message, UserConnection con, ChannelWrapper ch)
        {
            if (message.getData()[0] == 3) {
                // Send ACK
                ch.write( ForgeConstants.FML_ACK );
                con.getForgeHandshakeHandler().setServerIdList( message );
                return PRECOMPLETION;
            }
            
            return this;
        }

        @Override
        public ForgeServerHandshakeState send(PluginMessage message, UserConnection con, ChannelWrapper ch)
        {
            return this;
        }
        
    },
    PRECOMPLETION {

        @Override
        public ForgeServerHandshakeState handle(PluginMessage message, UserConnection con, ChannelWrapper ch)
        {
            if (message.getData()[0] == -1) {
                ch.write( ForgeConstants.FML_ACK );
                return DONE;
            }

            return this;
        }

        @Override
        public ForgeServerHandshakeState send(PluginMessage message, UserConnection con, ChannelWrapper ch)
        {
            return this;
        }
        
    },
    DONE {

        @Override
        public ForgeServerHandshakeState handle(PluginMessage message, UserConnection con, ChannelWrapper ch)
        {
            return this;
        }

        @Override
        public ForgeServerHandshakeState send(PluginMessage message, UserConnection con, ChannelWrapper ch)
        {
            return this;
        }
    }
}
