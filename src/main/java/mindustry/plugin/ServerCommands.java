package mindustry.plugin;

import arc.util.Log;
import mindustry.content.Mechs;
import mindustry.plugin.discordcommands.Command;
import mindustry.plugin.discordcommands.Context;
import mindustry.plugin.discordcommands.DiscordCommands;
import mindustry.plugin.discordcommands.RoleRestrictedCommand;
import arc.Core;
import arc.Events;
import arc.files.Fi;
import arc.struct.Array;

import mindustry.core.GameState;
import mindustry.entities.type.Player;
import mindustry.game.EventType.GameOverEvent;
import mindustry.game.Team;
import mindustry.gen.Call;
import mindustry.maps.Map;
import mindustry.maps.Maps;
import mindustry.io.SaveIO;
import mindustry.net.Administration;

import mindustry.type.Mech;
import org.javacord.api.entity.channel.TextChannel;
import org.javacord.api.entity.message.MessageAttachment;

import org.javacord.api.entity.message.embed.Embed;
import org.javacord.api.entity.message.embed.EmbedBuilder;
import org.json.JSONObject;

import java.io.*;
import java.lang.reflect.Field;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.zip.InflaterInputStream;

import static mindustry.Vars.*;

public class ServerCommands {

    private JSONObject data;
    private final Field mapsListField;

    public ServerCommands(JSONObject data){
        this.data = data;
        Class<Maps> mapsClass = Maps.class;
        Field mapsField;
        try {
            mapsField = mapsClass.getDeclaredField("maps");
        } catch (NoSuchFieldException ex) {
            throw new RuntimeException("Could not find field 'maps' of class 'mindustry.maps.Maps'");
        }
        mapsField.setAccessible(true);
        this.mapsListField = mapsField;
    }

    public void registerCommands(DiscordCommands handler) {

        handler.registerCommand(new Command("maps") {
            {
                help = "Check a list of available maps and their ids.";
            }
            public void run(Context ctx) {
                EmbedBuilder eb = new EmbedBuilder()
                        .setTitle("All available maps in the playlist:");
                Array<Map> mapList = maps.customMaps();
                for (int i = 0; i < mapList.size; i++) {
                    Map m = mapList.get(i);
                    eb.addField(String.valueOf(i), m.name() + " `" + m.width + " x " + m.height + "`");
                }
                ctx.channel.sendMessage(eb);
            }
        });
        if (data.has("administrator_roleid")) {
            String adminRole = data.getString("administrator_roleid");
            handler.registerCommand(new RoleRestrictedCommand("changemap"){
                {
                    help = "<mapname/mapid> Change the current map to the one provided.";
                    role = adminRole;
                }

                @SuppressWarnings("unchecked")
                public void run(Context ctx) {
                    EmbedBuilder eb = new EmbedBuilder();
                    if (ctx.args.length < 2) {
                        eb.setTitle("Command terminated.");
                        eb.setDescription("Not enough arguments, use `%changemap <mapname|mapid>`".replace("%", IoPlugin.prefix));
                        ctx.channel.sendMessage(eb);
                        return;
                    }
                    Map found = Utils.getMapBySelector(ctx.message.trim());
                    if (found == null) {
                        eb.setTitle("Command terminated.");
                        eb.setDescription("Map \"" + Utils.escapeCharacters(ctx.message.trim()) + "\" not found!");
                        ctx.channel.sendMessage(eb);
                        return;
                    }

                    Array<Map> mapsList;
                    try {
                        mapsList = (Array<Map>)mapsListField.get(maps);
                    } catch (IllegalAccessException ex) {
                        throw new RuntimeException("unreachable");
                    }

                    Map targetMap = found;
                    Array<Map> tempMapsList = mapsList.removeAll(map -> !map.custom || map != targetMap);

                    try {
                        mapsListField.set(maps, tempMapsList);
                    } catch (IllegalAccessException ex) {
                        throw new RuntimeException("unreachable");
                    }

                    Events.fire(new GameOverEvent(Team.crux));

                    try {
                        mapsListField.set(maps, mapsList);
                    } catch (IllegalAccessException ex) {
                        throw new RuntimeException("unreachable");
                    }

                    eb.setTitle("Command executed.");
                    eb.setDescription("Changed map to " + targetMap.name());
                    ctx.channel.sendMessage(eb);

                    maps.reload();
                }
            });

            handler.registerCommand(new RoleRestrictedCommand("setrank"){
                {
                    help = "<playerid|ip> <rank> Change the player's rank to the provided one.";
                    role = adminRole;
                }

                public void run(Context ctx) {
                    EmbedBuilder eb = new EmbedBuilder();
                    String target = ctx.args[1];
                    Integer targetRank = Integer.parseInt(ctx.args[2]);
                    if(target.length() > 0 && targetRank > -1 && targetRank < 5) {
                        Player player = Utils.findPlayer(target);
                        if(player!=null){
                            IoPlugin.database.put(player.uuid, targetRank);
                            eb.setTitle("Command executed successfully");
                            eb.setDescription("Promoted " + Utils.escapeCharacters(player.name) + " to " + targetRank);
                            ctx.channel.sendMessage(eb);
                            Call.onKick(player.con, "Your rank was modified, please rejoin.");
                        }
                    }
                }

            });
        }

        if (data.has("exit_roleid")) {
            handler.registerCommand(new RoleRestrictedCommand("exit") {
                {
                    help = "Close the server.";
                    role = data.getString("exit_roleid");
                }
                public void run(Context ctx) {
                    net.dispose();
                    Core.app.exit();
                }
            });
        }
        if (data.has("moderator_roleid")) {
            String banRole = data.getString("moderator_roleid");

            handler.registerCommand(new RoleRestrictedCommand("announce") {
                {
                    help = "<message> Announces a message to in-game chat.";
                }
                public void run(Context ctx) {
                    EmbedBuilder eb = new EmbedBuilder();
                    ctx.message = Utils.escapeCharacters(ctx.message);
                    if (ctx.message == null) {
                        eb.setTitle("Command terminated");
                        eb.setDescription("No message given");
                        ctx.channel.sendMessage(eb);
                        return;
                    }
                    if (ctx.message.length() < Utils.chatMessageMaxSize) {
                        Call.sendMessage("[scarlet]Admin announcement:[] " + ctx.message);
                        eb.setTitle("Command executed");
                        eb.setDescription("Your message was announced.");
                        ctx.channel.sendMessage(eb);
                    } else{
                        ctx.reply("Message too big.");
                    }
                }
            });

            handler.registerCommand(new RoleRestrictedCommand("gameover") {
                {
                    help = "Force a game over.";
                    role = banRole;
                }
                public void run(Context ctx) {
                    if (state.is(GameState.State.menu)) {
                        ctx.reply("Invalid state");
                        return;
                    }
                    Events.fire(new GameOverEvent(Team.crux));
                    EmbedBuilder eb = new EmbedBuilder()
                            .setTitle("Command executed.")
                            .setDescription("Done. New game starting in 10 seconds.");
                    ctx.channel.sendMessage(eb);
                }
            });

            handler.registerCommand(new RoleRestrictedCommand("ban") {
                {
                    help = "<ip/id> Ban a player by the provided ip or id.";
                    role = banRole;
                }
 
                public void run(Context ctx) {
                    EmbedBuilder eb = new EmbedBuilder()
                            .setTimestampToNow();
                    String target = ctx.args[1];
                    Boolean found = false;
                    int id = -1;
                    try {
                        id = Integer.parseInt(target);
                    } catch (NumberFormatException ex) {}
                    if (target.length() > 0) {
                        for (Player p : playerGroup.all()) {
                            if (p.con.address.equals(target) || p.id == id) {
                                found = true;
                                netServer.admins.banPlayer(p.uuid);
                                eb.setTitle("Command executed.");
                                eb.setDescription("Banned " + p.name + "(#" + p.id + ") `" + p.con.address + "` successfully!");
                                ctx.channel.sendMessage(eb);
                                Call.onKick(p.con, "You've been banned by: " + ctx.author.getName() + ". Appeal at http://discord.mindustry.io");
                                Call.sendChatMessage("[scarlet]" + Utils.escapeCharacters(p.name) + " has been banned.");
                                //Utils.LogAction("ban", "Remotely executed ban command", ctx.author, p.name + " : " + p.con.address);
                            }
                        }
                        if(!found){
                            eb.setTitle("Command terminated");
                            eb.setDescription("Player not online. Use %blacklist <ip> to ban an offline player.".replace("%", IoPlugin.prefix));
                            ctx.channel.sendMessage(eb);
                        }
                    } else {
                        eb.setTitle("Command terminated");
                        eb.setDescription("Not enough arguments / usage: `%ban <id/ip>`".replace("%", IoPlugin.prefix));
                        ctx.channel.sendMessage(eb);
                    }
                }
            });

            handler.registerCommand(new RoleRestrictedCommand("blacklist") {
                {
                    help = "<ip> Ban a player by the provided ip.";
                    role = banRole;
                }

                public void run(Context ctx) {
                    EmbedBuilder eb = new EmbedBuilder()
                            .setTimestampToNow();
                    String target = ctx.args[1];
                    if (target.length() > 0) {
                        netServer.admins.banPlayerIP(target);
                        eb.setTitle("Blacklisted successfully.");
                        eb.setDescription("`" + target + "` was banned.");
                    } else {
                        eb.setTitle("Command terminated");
                        eb.setDescription("Not enough arguments / usage: `%blacklist <ip>`".replace("%", IoPlugin.prefix));
                        ctx.channel.sendMessage(eb);
                    }
                }
            });
            handler.registerCommand(new RoleRestrictedCommand("unban") {
                EmbedBuilder eb = new EmbedBuilder();
                {
                    help = "Unban a player by the provided ip.";
                    role = banRole;
                }
                public void run(Context ctx) {
                    String ip;
                    if(ctx.args.length==2){ ip = ctx.args[1]; } else {ctx.reply("Invalid arguments provided, use the following format: %unban <ip>".replace("%", IoPlugin.prefix)); return;}

                    if (netServer.admins.unbanPlayerIP(ip)) {
                        eb.setTitle("Command executed.");
                        eb.setDescription("Unbanned `" + ip + "` successfully");
                        ctx.channel.sendMessage(eb);
                    } else {
                        eb.setTitle("Command terminated.");
                        eb.setDescription("No such ban exists.");
                        ctx.channel.sendMessage(eb);
                    }
                }
            });

            handler.registerCommand(new RoleRestrictedCommand("bans") {
                {
                    help = "Get info about all banned players.";
                    role = banRole;
                }
                public void run(Context ctx) {
                    List<String> result = new ArrayList<>();
                    Array<Administration.PlayerInfo> bans = netServer.admins.getBanned();
                    for (Administration.PlayerInfo playerInfo : bans) {
                        result.add("\n\n * Last seen IP: " + playerInfo.lastIP);
                        result.add("   All IPs:");
                        for (String ip : playerInfo.ips) result.add("    * " + ip);
                        result.add("   All names:");
                        for (String name : playerInfo.names) result.add("    * " + Utils.escapeCharacters(name));
                    }

                    File f = new File(new SimpleDateFormat("yyyy_MM_dd").format(Calendar.getInstance().getTime()) + "_IO_BANS.txt");
                    try {
                        FileWriter fw;
                        fw = new FileWriter(f.getAbsoluteFile());
                        BufferedWriter bw = new BufferedWriter(fw);
                        bw.write(Utils.constructMessage(result));
                        bw.close();
                        ctx.channel.sendMessage(f);
                        f.delete();
                    } catch (IOException e) {
                        ctx.reply("An error occurred.");
                        e.printStackTrace();
                    }
                }
            });

            handler.registerCommand(new RoleRestrictedCommand("kick") {
                EmbedBuilder eb = new EmbedBuilder()
                        .setTimestampToNow();
                {
                    help = "<ip/id> Kick a player by the provided ip or id.";
                    role = banRole;
                }
                public void run(Context ctx) {
                    String target;
                    if(ctx.args.length==2){ target = ctx.args[1]; } else {ctx.reply("Invalid arguments provided, use the following format: %kick <ip/id>".replace("%", IoPlugin.prefix)); return;}

                    int id = -1;
                    try {
                        id = Integer.parseInt(target);
                    } catch (NumberFormatException ignored) {}
                    if (target.length() > 0) {
                        for (Player p : playerGroup.all()) {
                            if (p.con.address.equals(target) || p.id == id) {
                                eb.setTitle("Command executed.");
                                eb.setDescription("Kicked " + p.name + "(#" + p.id + ") `" + p.con.address + "` successfully.");
                                Call.sendChatMessage("[scarlet]" + Utils.escapeCharacters(p.name) + " has been kicked.");
                                Call.onKick(p.con, "You've been kicked by: " + ctx.author.getName());
                                ctx.channel.sendMessage(eb);
                                //Utils.LogAction("kick", "Remotely executed kick command", ctx.author, p.name + " : " + p.con.address);
                            }
                        }
                    } else {
                        eb.setTitle("Command terminated.");
                        eb.setDescription("Not enough arguments / usage: `kick <ip/id>`");
                        ctx.channel.sendMessage(eb);
                    }
                }
            });

            handler.registerCommand(new RoleRestrictedCommand("playersinfo") {
                {
                    help = "Check the information about all players on the server.";
                    role = banRole;
                }
                public void run(Context ctx) {
                    StringBuilder msg = new StringBuilder("**Players online: " + playerGroup.size() + "**\n```\n");
                    for (Player player : playerGroup.all()) {
                        msg.append("· ").append(Utils.escapeCharacters(player.name));
                        if(!player.isAdmin) {
                            msg.append(" : ").append(player.con.address).append("\n");
                        }
                    }
                    msg.append("```");

                    ctx.channel.sendMessage(msg.toString());
                }
            });

            handler.registerCommand(new RoleRestrictedCommand("uploadmap") {
                {
                    help = "<.msav attachment> Upload a new map (Include a .msav file with command message)";
                    role = banRole;
                }
                public void run(Context ctx) {
                    EmbedBuilder eb = new EmbedBuilder();
                    Array<MessageAttachment> ml = new Array<>();
                    for (MessageAttachment ma : ctx.event.getMessageAttachments()) {
                        if (ma.getFileName().split("\\.", 2)[1].trim().equals("msav")) {
                            ml.add(ma);
                        }
                    }
                    if (ml.size != 1) {
                        eb.setTitle("Map upload terminated.");
                        eb.setDescription("You need to add one valid .msav file!");
                        ctx.channel.sendMessage(eb);
                        return;
                    } else if (Core.settings.getDataDirectory().child("maps").child(ml.get(0).getFileName()).exists()) {
                        eb.setTitle("Map upload terminated.");
                        eb.setDescription("There is already a map with this name on the server!");
                        ctx.channel.sendMessage(eb);
                        return;
                    }
                    // more custom filename checks possible

                    CompletableFuture<byte[]> cf = ml.get(0).downloadAsByteArray();
                    Fi fh = Core.settings.getDataDirectory().child("maps").child(ml.get(0).getFileName());

                    try {
                        byte[] data = cf.get();
                        if (!SaveIO.isSaveValid(new DataInputStream(new InflaterInputStream(new ByteArrayInputStream(data))))) {
                            eb.setTitle("Map upload terminated.");
                            eb.setDescription("Map file corrupted or invalid.");
                            ctx.channel.sendMessage(eb);
                            return;
                        }
                        fh.writeBytes(cf.get(), false);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                    maps.reload();
                    eb.setTitle("Map upload completed.");
                    eb.setDescription(ml.get(0).getFileName() + " was added succesfully into the playlist!");
                    ctx.channel.sendMessage(eb);
                    //Utils.LogAction("uploadmap", "Uploaded a new map", ctx.author, null);
                }
            });
            handler.registerCommand(new RoleRestrictedCommand("removemap") {
                {
                    help = "<mapname/mapid> Remove a map from the playlist (use mapname/mapid retrieved from the %maps command)".replace("%", IoPlugin.prefix);
                    role = banRole;
                }
                @Override
                public void run(Context ctx) {
                    EmbedBuilder eb = new EmbedBuilder();
                    if (ctx.args.length < 2) {
                        eb.setTitle("Command terminated.");
                        eb.setDescription("Not enough arguments, use `%removemap <mapname/mapid>`".replace("%", IoPlugin.prefix));
                        ctx.channel.sendMessage(eb);
                        return;
                    }
                    Map found = Utils.getMapBySelector(ctx.message.trim());
                    if (found == null) {
                        eb.setTitle("Command terminated.");
                        eb.setDescription("Map not found");
                        ctx.channel.sendMessage(eb);
                        return;
                    }

                    maps.removeMap(found);
                    maps.reload();

                    eb.setTitle("Command executed.");
                    eb.setDescription(found.name() + " was successfully removed from the playlist.");
                    ctx.channel.sendMessage(eb);
                }
            });
            handler.registerCommand(new RoleRestrictedCommand("syncserver") {
                {
                    help = "Attempt to fix the server without interrupting active connections.";
                    role = banRole;
                }
                public void run(Context ctx) {
                    for(Player p : playerGroup.all()) {
                        netServer.sendWorldData(p);
                    }
                    EmbedBuilder eb = new EmbedBuilder()
                            .setTitle("Command executed.")
                            .setDescription("Synchronized every player's client with the server.");
                    ctx.channel.sendMessage(eb);
                }
            });

            handler.registerCommand(new RoleRestrictedCommand("mech") {
                {
                    help = "<mechname> <playerid|ip|all> Change the provided player into a specific mech.";
                    role = banRole;
                }
                public void run(Context ctx) {
                    String target = ctx.args[1];
                    String targetMech = ctx.args[2].toLowerCase();
                    Mech desiredMech = Mechs.alpha;
                    if(target.length() > 0 && targetMech.length() > 0) {
                        switch(targetMech){
                            case "alpha":
                                break;
                            case "delta":
                                desiredMech = Mechs.delta;
                                break;
                            case "tau":
                                desiredMech = Mechs.tau;
                                break;
                            case "dart":
                                desiredMech = Mechs.dart;
                                break;
                            case "glaive":
                                desiredMech = Mechs.glaive;
                                break;
                            case "javelin":
                                desiredMech = Mechs.javelin;
                                break;
                            case "omega":
                                desiredMech = Mechs.omega;
                                break;
                            case "trident":
                                desiredMech = Mechs.trident;
                                break;
                            default:
                                desiredMech = Mechs.starter;
                                break;
                        }

                        EmbedBuilder eb = new EmbedBuilder();

                        if(target.equals("all")) {
                            for (Player p : playerGroup.all()) {
                                p.mech = desiredMech;
                            }
                            eb.setTitle("Command executed successfully.");
                            eb.setDescription("Changed everyone's mech into " + desiredMech.name);
                            ctx.channel.sendMessage(eb);
                            return;
                        }
                        Player player = Utils.findPlayer(target);
                        if(player!=null){
                            player.mech = desiredMech;
                            eb.setTitle("Command executed successfully.");
                            eb.setDescription("Changed " + Utils.escapeCharacters(player.name) + "s mech into " + desiredMech.name);
                            ctx.channel.sendMessage(eb);
                        }
                    }
                }
            });
            //TODO: add a lot of commands that moderators can use to mess with players real-time (e. kill, freeze, teleport, etc.)
        }


        if(data.has("mapSubmissions_id")){
            TextChannel tc = IoPlugin.getTextChannel(IoPlugin.data.getString("mapSubmissions_id"));
            handler.registerCommand(new Command("submitmap") {
                {
                    help = "<.msav attachment> Submit a new map to be added into the server playlist in a .msav file format.";
                }
                public void run(Context ctx) {
                    EmbedBuilder eb = new EmbedBuilder();
                    Array<MessageAttachment> ml = new Array<>();
                    for (MessageAttachment ma : ctx.event.getMessageAttachments()) {
                        if (ma.getFileName().split("\\.", 2)[1].trim().equals("msav")) {
                            ml.add(ma);
                        }
                    }
                    if (ml.size != 1) {
                        eb.setTitle("Map upload terminated.");
                        eb.setDescription("You need to add one valid .msav file!");
                        ctx.channel.sendMessage(eb);
                        return;
                    } else if (Core.settings.getDataDirectory().child("maps").child(ml.get(0).getFileName()).exists()) {
                        eb.setTitle("Map upload terminated.");
                        eb.setDescription("There is already a map with this name on the server!");
                        ctx.channel.sendMessage(eb);
                        return;
                    }
                    CompletableFuture<byte[]> cf = ml.get(0).downloadAsByteArray();

                    try {
                        byte[] data = cf.get();
                        if (!SaveIO.isSaveValid(new DataInputStream(new InflaterInputStream(new ByteArrayInputStream(data))))) {
                            eb.setTitle("Map upload terminated.");
                            eb.setDescription("Map file corrupted or invalid.");
                            ctx.channel.sendMessage(eb);
                            return;
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                    eb.setTitle("Map upload completed.");
                    eb.setDescription(ml.get(0).getFileName() + " was successfully queued for review by moderators!");
                    ctx.channel.sendMessage(eb);
                    EmbedBuilder eb2 = new EmbedBuilder()
                            .setTitle("A map submission has been made for " + IoPlugin.serverName)
                            .setAuthor(ctx.author)
                            .setTimestampToNow()
                            .addField("Name", ml.get(0).getFileName())
                            .addField("URL", String.valueOf(ml.get(0).getUrl()));
                    tc.sendMessage(eb2);
                }
            });
        }
    }
}