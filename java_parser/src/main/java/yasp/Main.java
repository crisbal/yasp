package yasp;

import com.google.gson.Gson;
import com.google.protobuf.GeneratedMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import skadistats.clarity.decoder.Util;
import skadistats.clarity.model.Entity;
import skadistats.clarity.model.FieldPath;
import skadistats.clarity.model.s1.GameRulesStateType;
import skadistats.clarity.processor.entities.Entities;
import skadistats.clarity.processor.entities.OnEntityEntered;
import skadistats.clarity.processor.entities.UsesEntities;
import skadistats.clarity.processor.gameevents.CombatLog;
import skadistats.clarity.processor.gameevents.OnCombatLogEntry;
import skadistats.clarity.processor.reader.OnMessage;
import skadistats.clarity.processor.reader.OnTickStart;
import skadistats.clarity.processor.runner.Context;
import skadistats.clarity.processor.runner.SimpleRunner;
import skadistats.clarity.model.CombatLogEntry;
import skadistats.clarity.source.InputStreamSource;
import skadistats.clarity.wire.common.proto.Demo.CDemoFileInfo;
import skadistats.clarity.wire.common.proto.Demo.CGameInfo.CDotaGameInfo.CPlayerInfo;
import skadistats.clarity.wire.common.proto.DotaUserMessages.CDOTAUserMsg_ChatEvent;
import skadistats.clarity.wire.common.proto.DotaUserMessages.CDOTAUserMsg_LocationPing;
import skadistats.clarity.wire.common.proto.DotaUserMessages.CDOTAUserMsg_SpectatorPlayerUnitOrders;
import skadistats.clarity.wire.common.proto.DotaUserMessages.DOTA_COMBATLOG_TYPES;
import skadistats.clarity.wire.s2.proto.S2UserMessages.CUserMessageSayText2;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

public class Main {
    private final Logger log = LoggerFactory.getLogger(Main.class.getPackage().getClass());
    float INTERVAL = 1;
    float nextInterval = 0;
    Integer time = 0;
    int numPlayers = 10;
    EventStream es = new EventStream();
    int[] validIndices = new int[numPlayers];
    boolean init = false;
    int gameStartTime = 0;
    //Set<Integer> seenEntities = new HashSet<Integer>();
    private Gson g = new Gson();
    private List<Entry> es;
    HashMap<Integer, Integer> slot_to_playerslot = new HashMap<Integer, Integer>();
    HashMap<String, Integer> name_to_slot = new HashMap<String, Integer>();

    //@OnMessage(GeneratedMessage.class)
    public void onMessage(Context ctx, GeneratedMessage message) {
        System.err.println(message.getClass().getName());
        System.out.println(message.toString());
    }

    /*
    //@OnMessage(CDOTAUserMsg_SpectatorPlayerClick.class)
    public void onSpectatorPlayerClick(Context ctx, CDOTAUserMsg_SpectatorPlayerClick message){
        Entry entry = new Entry(time);
        entry.type = "clicks";
        //need to get the entity by index
        entry.key = String.valueOf(message.getOrderType());
        //theres also target_index
        output(entry);
    }
    */

    @OnMessage(CDOTAUserMsg_SpectatorPlayerUnitOrders.class)
    public void onSpectatorPlayerUnitOrders(Context ctx, CDOTAUserMsg_SpectatorPlayerUnitOrders message) {
        Entry entry = new Entry(time);
        entry.type = "actions";
        //the entindex points to a CDOTAPlayer.  This is probably the player that gave the order.
        Entity e = ctx.getProcessor(Entities.class).getByIndex(message.getEntindex());
        entry.slot = getEntityProperty(e, "m_iPlayerID", null);
        //Integer handle = (Integer)getEntityProperty(e, "m_hAssignedHero", null);
        //Entity h = ctx.getProcessor(Entities.class).getByHandle(handle);
        //System.err.println(h.getDtClass().getDtName());
        //break actions into types?
        entry.key = String.valueOf(message.getOrderType());
        //System.err.println(message);
        output(entry);
    }


    @OnMessage(CDOTAUserMsg_LocationPing.class)
    public void onPlayerPing(Context ctx, CDOTAUserMsg_LocationPing message) {
        Entry entry = new Entry(time);
        entry.type = "pings";
        entry.slot = message.getPlayerId();
        /*
        System.err.println(message);
        player_id: 7
        location_ping {
          x: 5871
          y: 6508
          target: -1
          direct_ping: false
          type: 0
        }
        */
        //we could get the ping coordinates/type if we cared
        //entry.key = String.valueOf(message.getOrderType());
        output(entry);
    }

    @OnMessage(CDOTAUserMsg_ChatEvent.class)
    public void onChatEvent(Context ctx, CDOTAUserMsg_ChatEvent message) {
        Integer player1 = message.getPlayerid1();
        Integer player2 = message.getPlayerid2();
        Integer value = message.getValue();
        String type = String.valueOf(message.getType());
        Entry entry = new Entry(time);
        entry.type = type;
        entry.player1 = player1;
        entry.player2 = player2;
        entry.value = value;
        output(entry);
    }

    /*
    @OnMessage(CUserMsg_SayText2.class)
    public void onAllChatS1(Context ctx, CUserMsg_SayText2 message) {
        Entry entry = new Entry(time);
        entry.unit =  String.valueOf(message.getPrefix());
        entry.key =  String.valueOf(message.getText());
        entry.type = "chat";
        output(entry);
    }
    */

    @OnMessage(CUserMessageSayText2.class)
    public void onAllChatS2(Context ctx, CUserMessageSayText2 message) {
        Entry entry = new Entry(time);
        entry.unit = String.valueOf(message.getParam1());
        entry.key = String.valueOf(message.getParam2());
        Entity e = ctx.getProcessor(Entities.class).getByIndex(message.getEntityindex());
        entry.slot = getEntityProperty(e, "m_iPlayerID", null);
        entry.type = "chat";
        output(entry);
    }

    @OnMessage(CDemoFileInfo.class)
    public void onFileInfo(Context ctx, CDemoFileInfo message) {
        //beware of 4.2b limit!  we don't currently do anything with this, so we might be able to just remove this
        //Entry matchIdEntry = new Entry();
        //matchIdEntry.type = "match_id";
        //matchIdEntry.value = message.getGameInfo().getDota().getMatchId();
        //output(matchIdEntry);

        //emit epilogue event to mark finish
        Entry epilogueEntry = new Entry();
        epilogueEntry.type = "epilogue";
        epilogueEntry.key = new Gson().toJson(message);
        output(epilogueEntry);
    }

    @OnCombatLogEntry
    public void onCombatLogEntry(Context ctx, CombatLogEntry cle) {
        time = Math.round(cle.getTimestamp());
        //create a new entry
        Entry combatLogEntry = new Entry(time);
        combatLogEntry.type = cle.getType().name();
        //translate the fields using string tables if necessary (get*Name methods)
        combatLogEntry.attackername = cle.getAttackerName();
        combatLogEntry.targetname = cle.getTargetName();
        combatLogEntry.sourcename = cle.getDamageSourceName();
        combatLogEntry.targetsourcename = cle.getTargetSourceName();
        combatLogEntry.inflictor = cle.getInflictorName();
        combatLogEntry.gold_reason = cle.getGoldReason();
        combatLogEntry.xp_reason = cle.getXpReason();
        combatLogEntry.attackerhero = cle.isAttackerHero();
        combatLogEntry.targethero = cle.isTargetHero();
        combatLogEntry.attackerillusion = cle.isAttackerIllusion();
        combatLogEntry.targetillusion = cle.isTargetIllusion();
        combatLogEntry.value = cle.getValue();
        //value may be out of bounds in string table, we can only get valuename if a purchase (type 11)
        if (cle.getType() == DOTA_COMBATLOG_TYPES.DOTA_COMBATLOG_PURCHASE) {
            combatLogEntry.valuename = cle.getValueName();
        }
        if (cle.getType() == DOTA_COMBATLOG_TYPES.DOTA_COMBATLOG_GAME_STATE && cle.getValue() == 5){
            gameStartTime = time;
        }
        output(combatLogEntry);
        
        if (cle.getType().ordinal() > 19) {
            System.err.println(cle);
        }
    }

    @OnEntityEntered
    public void onEntityEntered(Context ctx, Entity e) {
        //CDOTA_NPC_Observer_Ward
        //CDOTA_NPC_Observer_Ward_TrueSight
        //s1 "DT_DOTA_NPC_Observer_Ward"
        //s1 "DT_DOTA_NPC_Observer_Ward_TrueSight"
        boolean isObserver = e.getDtClass().getDtName().equals("CDOTA_NPC_Observer_Ward");
        boolean isSentry = e.getDtClass().getDtName().equals("CDOTA_NPC_Observer_Ward_TrueSight");
        if (isObserver || isSentry) {
            //System.err.println(e);
            Entry entry = new Entry(time);
            Integer x = getEntityProperty(e, "CBodyComponent.m_cellX", null);
            Integer y = getEntityProperty(e, "CBodyComponent.m_cellY", null);
            Integer[] pos = {x, y};
            entry.type = isObserver ? "obs" : "sen";
            entry.key = Arrays.toString(pos);
            //System.err.println(entry.key);
            Integer owner = getEntityProperty(e, "m_hOwnerEntity", null);
            Entity ownerEntity = ctx.getProcessor(Entities.class).getByHandle(owner);
            entry.slot = ownerEntity != null ? (Integer) getEntityProperty(ownerEntity, "m_iPlayerID", null) : null;
            //2/3 radiant/dire
            //entry.team = e.getProperty("m_iTeamNum");
            output(entry);
        }
    }

    @UsesEntities
    @OnTickStart
    public void onTickStart(Context ctx, boolean synthetic) {
        //s1 DT_DOTAGameRulesProxy
        Entity grp = ctx.getProcessor(Entities.class).getByDtName("CDOTAGamerulesProxy");
        Entity pr = ctx.getProcessor(Entities.class).getByDtName("CDOTA_PlayerResource");
        Entity dData = ctx.getProcessor(Entities.class).getByDtName("CDOTA_DataDire");
        Entity rData = ctx.getProcessor(Entities.class).getByDtName("CDOTA_DataRadiant");
        if (grp != null) {
            //System.err.println(grp);
            //dota_gamerules_data.m_iGameMode = 22
            //dota_gamerules_data.m_unMatchID64 = 1193091757
            time = Math.round((float) getEntityProperty(grp, "m_pGameRules.m_fGameTime", null));
            //alternate to combat log for getting game zero time (looks like this is set at the same time as the game start, so it's not any better for streaming)
            /*
            int currGameStartTime = Math.round( (float) grp.getProperty("m_pGameRules.m_flGameStartTime"));
            if (currGameStartTime != gameStartTime){
                gameStartTime = currGameStartTime;
                System.err.println(gameStartTime);
                System.err.println(time);
            }
            */
        }
        if (pr != null) {
            //Radiant coach shows up in vecPlayerTeamData as position 5
            //all the remaining dire entities are offset by 1 and so we miss reading the last one and don't get data for the first dire player
            //coaches appear to be on team 1, radiant is 2 and dire is 3?
            //construct an array of valid indices to get vecPlayerTeamData from
            if (!init) {
                int added = 0;
                int i = 0;
                //according to @Decoud Valve seems to have fixed this issue and players should be in first 10 slots again
                //sanity check of i to prevent infinite loop when <10 players?
                while (added < numPlayers && i < 100) {
                    //check each m_vecPlayerData to ensure the player's team is radiant or dire
                    int playerTeam = getEntityProperty(pr, "m_vecPlayerData.%i.m_iPlayerTeam", i);
                    int teamSlot = getEntityProperty(pr, "m_vecPlayerTeamData.%i.m_iTeamSlot", i);
                    Long steamid = getEntityProperty(pr, "m_vecPlayerData.%i.m_iPlayerSteamID", i);
                    //System.err.format("%s %s %s: %s\n", i, playerTeam, teamSlot, steamid);
                    if (playerTeam == 2 || playerTeam == 3) {
                        //output the player_slot based on team and teamslot
                        Entry entry = new Entry(time);
                        entry.type = "player_slot";
                        entry.key = String.valueOf(added);
                        entry.value = (playerTeam == 2 ? 0 : 128) + teamSlot;
                        output(entry);
                        slot_to_playerslot.put(added, entry.value);
                        //add it to validIndices, add 1 to added
                        validIndices[added] = i;
                        added += 1;
                    }

                    i += 1;
                }
                init = true;
            }

            if (time >= nextInterval) {
                //System.err.println(pr);
                for (int i = 0; i < numPlayers; i++) {
                    Integer hero = getEntityProperty(pr, "m_vecPlayerTeamData.%i.m_nSelectedHeroID", validIndices[i]);
                    int handle = getEntityProperty(pr, "m_vecPlayerTeamData.%i.m_hSelectedHero", validIndices[i]);
                    int playerTeam = getEntityProperty(pr, "m_vecPlayerData.%i.m_iPlayerTeam", validIndices[i]);
                    int teamSlot = getEntityProperty(pr, "m_vecPlayerTeamData.%i.m_iTeamSlot", validIndices[i]);
                    //System.err.format("hero:%s i:%s teamslot:%s playerteam:%s\n", hero, i, teamSlot, playerTeam);
                
                    //2 is radiant, 3 is dire, 1 is other?
                    Entity dataTeam = playerTeam == 2 ? rData : dData;

                    Entry entry = new Entry(time);
                    entry.type = "interval";
                    entry.slot = i;

                    entry.gold = getEntityProperty(dataTeam, "m_vecDataTeam.%i.m_iTotalEarnedGold", teamSlot);
                    entry.lh = getEntityProperty(dataTeam, "m_vecDataTeam.%i.m_iLastHitCount", teamSlot);
                    entry.xp = getEntityProperty(dataTeam, "m_vecDataTeam.%i.m_iTotalEarnedXP", teamSlot);
                    entry.stuns = getEntityProperty(dataTeam, "m_vecDataTeam.%i.m_fStuns", teamSlot);

                    //TODO: gem, rapier time?
                    //https://github.com/yasp-dota/yasp/issues/333
                    //need to dump inventory items for each player and possibly keep track of item entity handles

                    //time dead, count number of intervals where this value is >0?
                    //m_iRespawnSeconds.0000

                    //get the player's hero entity
                    Entity e = ctx.getProcessor(Entities.class).getByHandle(handle);
                    //get the hero's coordinates
                    if (e != null) {
                        //System.err.println(e);
                        entry.x = getEntityProperty(e, "CBodyComponent.m_cellX", null);
                        entry.y = getEntityProperty(e, "CBodyComponent.m_cellY", null);
                        //System.err.format("%s, %s\n", entry.x, entry.y);
                        //check if hero has been assigned to entity
                        if (hero > 0)
                        {
                            //get the hero's entity name, ex: CDOTA_Hero_Zuus
                            String unit = e.getDtClass().getDtName();
                            //grab the end of the name, lowercase it
                            String ending = e.unit.substring("CDOTA_Unit_Hero_".length());
                            //valve is bad at consistency and the combat log name could involve replacing camelCase with _ or not!
                            //double map it so we can look up both cases
                            String combatLogName = "npc_dota_hero_" + ending.toLowerCase();
                            //don't include final underscore here since the first letter is always capitalized and will be converted to underscore
                            String combatLogName2 = "npc_dota_hero" + ending.replaceAll("([A-Z])", "_$1").toLowerCase();
                            System.err.format("%s, %s\n", combatLogName, combatLogName2);
                            //populate for combat log mapping
                            name_to_slot.put(combatLogName, entry.slot);
                            name_to_slot.put(combatLogName2, entry.slot);
                        }
                    }
                    output(entry);
                }
                nextInterval += INTERVAL;
            }
        }
    }

    public <T> T getEntityProperty(Entity e, String property, Integer idx) {
        if (e == null) {
            return null;
        }
        if (idx != null) {
            property = property.replace("%i", Util.arrayIdxToString(idx));
        }
        FieldPath fp = e.getDtClass().getFieldPathForName(property);
        return e.getPropertyForFieldPath(fp);
    }

    public void output(Entry e) {
        es.add(e);
    }
    
    public void flush(){
        List<OutputEntry> outputBuf = new List<OutputEntry>();
        for (Entry e : es) {
            e.time -= gameStartTime;
            switch (e.type) {
              case "DOTA_COMBATLOG_DAMAGE":
                  e.player_slot = slot_to_playerslot.get(name_to_slot.get(e.sourcename));
                  e.key = computeIllusionString(e.targetname, e.targetillusion);
                  e.type = "damage";
                  outputBuf.add(new OutputEntry(e));
                  //check if this damage happened to a real hero
                  if (e.targethero && !e.targetillusion)
                  {
                      //reverse and count as damage taken (see comment for reversed kill)
                      var r = {
                          time: e.time,
                          unit: e.key,
                          key: e.unit,
                          value: e.value,
                          type: "damage_taken"
                      };
                      populate(r);
                      //count a hit on a real hero with this inflictor
                      var h = {
                          time: e.time,
                          unit: e.unit,
                          key: translate(e.inflictor),
                          type: "hero_hits"
                      };
                      populate(h);
                      //don't count self-damage for the following
                      if (e.key !== e.unit)
                      {
                          //count damage dealt to a real hero with this inflictor
                          var inf = {
                              type: "damage_inflictor",
                              time: e.time,
                              unit: e.unit,
                              key: translate(e.inflictor),
                              value: e.value
                          };
                          populate(inf);
                          //biggest hit on a hero
                          var m = {
                              type: "max_hero_hit",
                              time: e.time,
                              max: true,
                              inflictor: translate(e.inflictor),
                              unit: e.unit,
                              key: e.key,
                              value: e.value
                          };
                          populate(m);
                      }
                  }
            }
                  break;
                  default:
                  break;
            }
        }
        for (OutputEntry oe : outputBuf){
            System.out.print(g.toJson(oe) + "\n");
        }
    }
    
    public void run(String[] args) throws Exception {
        long tStart = System.currentTimeMillis();
        new SimpleRunner(new InputStreamSource(System.in)).runWith(this);
        flush();
        long tMatch = System.currentTimeMillis() - tStart;
        System.err.format("total time taken: %s\n", (tMatch) / 1000.0);
    }

    public static void main(String[] args) throws Exception {
        new Main().run(args);
    }
}

            "DOTA_COMBATLOG_HEAL": function(e)
            {
                //healing
                e.unit = e.sourcename; //source of healing (a hero)
                e.key = computeIllusionString(e.targetname, e.targetillusion);
                e.type = "healing";
                populate(e);
            },
            "DOTA_COMBATLOG_MODIFIER_ADD": function(e)
            {
                //gain buff/debuff
                e.unit = e.attackername; //unit that buffed (can we use source to get the hero directly responsible? chen/enchantress/etc.)
                e.key = translate(e.inflictor); //the buff
                //e.targetname is target of buff (possibly illusion)
                e.type = "modifier_applied";
                populate(e);
            },
            "DOTA_COMBATLOG_MODIFIER_REMOVE": function(e)
            {
                //lose buff/debuff
                //TODO: do something with modifier lost events, really only useful if we want to try to "time" modifiers
                //e.targetname is unit losing buff (possibly illusion)
                //e.inflictor is name of buff
                e.type = "modifier_lost";
            },
            "DOTA_COMBATLOG_DEATH": function(e)
            {
                //kill
                e.unit = e.sourcename; //killer (a hero)
                e.key = computeIllusionString(e.targetname, e.targetillusion);
                e.type = "killed";
                populate(e);
                //killed unit was a real hero
                if (e.targethero && !e.targetillusion)
                {
                    //log this hero kill
                    var e2 = JSON.parse(JSON.stringify(e));
                    e2.type = "kills_log";
                    populate(e2);
                    //reverse and count as killed by
                    //if the killed unit isn't a hero, we don't care about killed_by
                    var r = {
                        time: e.time,
                        unit: e.key,
                        key: e.unit,
                        type: "killed_by"
                    };
                    populate(r);
                }
            },
            "DOTA_COMBATLOG_ABILITY": function(e)
            {
                //ability use
                e.unit = e.attackername;
                e.key = translate(e.inflictor);
                e.type = "ability_uses";
                populate(e);
            },
            "DOTA_COMBATLOG_ITEM": function(e)
            {
                //item use
                e.unit = e.attackername;
                e.key = translate(e.inflictor);
                e.type = "item_uses";
                populate(e);
            },
            "DOTA_COMBATLOG_LOCATION": function(e)
            {
                //TODO not in replay?
                console.log(e);
            },
            "DOTA_COMBATLOG_GOLD": function(e)
            {
                //gold gain/loss
                e.unit = e.targetname;
                e.key = e.gold_reason;
                //gold_reason=8 is cheats, not added to constants
                e.type = "gold_reasons";
                populate(e);
            },
            "DOTA_COMBATLOG_GAME_STATE": function(e)
            {
                //state
                //we don't use this here--we already used it during preprocessing to detect game_zero
                e.type = "state";
            },
            "DOTA_COMBATLOG_XP": function(e)
            {
                //xp gain
                e.unit = e.targetname;
                e.key = e.xp_reason;
                e.type = "xp_reasons";
                populate(e);
            },
            "DOTA_COMBATLOG_PURCHASE": function(e)
            {
                //purchase
                e.unit = e.targetname;
                e.key = translate(e.valuename);
                e.value = 1;
                e.type = "purchase";
                populate(e);
                //don't include recipes in purchase logs
                if (e.key.indexOf("recipe_") !== 0)
                {
                    var e2 = JSON.parse(JSON.stringify(e));
                    e2.type = "purchase_log";
                    populate(e2);
                }
            },
            "DOTA_COMBATLOG_BUYBACK": function(e)
            {
                //buyback
                e.slot = e.value; //player slot that bought back
                e.type = "buyback_log";
                populate(e);
            },
            "DOTA_COMBATLOG_ABILITY_TRIGGER": function(e)
            {
                //only seems to happen for axe spins
                e.type = "ability_trigger";
                //e.attackername //unit triggered on?
                //e.key = e.inflictor; //ability triggered?
                //e.unit = determineIllusion(e.targetname, e.targetillusion); //unit that triggered the skill
            },
            "DOTA_COMBATLOG_PLAYERSTATS": function(e)
            {
                //player stats
                //TODO: don't really know what this does, following fields seem to be populated
                //attackername
                //targetname
                //targetsourcename
                //value (1-15)
                e.type = "player_stats";
                e.unit = e.attackername;
                e.key = e.targetname;
            },
            "DOTA_COMBATLOG_MULTIKILL": function(e)
            {
                //multikill
                e.unit = e.attackername;
                e.key = e.value;
                e.value = 1;
                e.type = "multi_kills";
                populate(e);
            },
            "DOTA_COMBATLOG_KILLSTREAK": function(e)
            {
                //killstreak
                e.unit = e.attackername;
                e.key = e.value;
                e.value = 1;
                e.type = "kill_streaks";
                populate(e);
            },
            "DOTA_COMBATLOG_TEAM_BUILDING_KILL": function(e)
            {
                //team building kill
                //System.err.println(cle);
                e.type = "team_building_kill";
                e.unit = e.attackername; //unit that killed the building
                //e.value, this is only really useful if we can get WHICH tower/rax was killed
                //0 is other?
                //1 is tower?
                //2 is rax?
                //3 is ancient?
            },
            "DOTA_COMBATLOG_FIRST_BLOOD": function(e)
            {
                //first blood
                e.type = "first_blood";
                //time, involved players?
            },
            "DOTA_COMBATLOG_MODIFIER_REFRESH": function(e)
            {
                //modifier refresh
                e.type = "modifier_refresh";
                //no idea what this means
            },
            "clicks": function(e)
            {
                populate(e);
            },
            "pings": function(e)
            {
                //we're not breaking pings into subtypes atm so just set key to 0 for now
                e.key = 0;
                populate(e);
            },
            "actions": function(e)
            {
                populate(e);
            },
            "CHAT_MESSAGE_RUNE_PICKUP": function(e)
            {
                e.type = "runes";
                e.slot = e.player1;
                e.key = e.value.toString();
                e.value = 1;
                populate(e);
            },
            "CHAT_MESSAGE_RUNE_BOTTLE": function(e)
            {
                //not tracking rune bottling atm
            },
            "CHAT_MESSAGE_HERO_KILL": function(e)
            {
                //player, assisting players
                //player2 killed player 1
                //subsequent players assisted
                //still not perfect as dota can award kills to players when they're killed by towers/creeps and chat event does not reflect this
                //e.slot = e.player2;
                //e.key = e.player1.toString();
                //currently disabled in favor of combat log kills
                //populate(e);
            },
            "CHAT_MESSAGE_GLYPH_USED": function(e)
            {
                //team glyph
                //player1 = team that used glyph (2/3, or 0/1?)
                //e.team = e.player1;
            },
            "CHAT_MESSAGE_PAUSED": function(e)
            {
                //e.slot = e.player1;
                //player1 paused
            },
            "CHAT_MESSAGE_TOWER_KILL": function(e)
            {
                e.team = e.value;
                e.slot = e.player1;
                parsed_data.objectives.push(JSON.parse(JSON.stringify(e)));
            },
            "CHAT_MESSAGE_TOWER_DENY": function(e)
            {
                //tower (player/team)
                //player1 = slot of player who killed tower (-1 if nonplayer)
                //value (2/3 radiant/dire killed tower, recently 0/1?)
                e.team = e.value;
                e.slot = e.player1;
                parsed_data.objectives.push(JSON.parse(JSON.stringify(e)));
            },
            "CHAT_MESSAGE_BARRACKS_KILL": function(e)
            {
                //barracks (player)
                //value id of barracks based on power of 2?
                //Barracks can always be deduced 
                //They go in incremental powers of 2, starting by the Dire side to the Dire Side, Bottom to Top, Melee to Ranged
                //so Bottom Melee Dire Rax = 1 and Top Ranged Radiant Rax = 2048.
                e.key = e.value.toString();
                parsed_data.objectives.push(JSON.parse(JSON.stringify(e)));
            },
            "CHAT_MESSAGE_FIRSTBLOOD": function(e)
            {
                e.slot = e.player1;
                parsed_data.objectives.push(JSON.parse(JSON.stringify(e)));
            },
            "CHAT_MESSAGE_AEGIS": function(e)
            {
                e.slot = e.player1;
                parsed_data.objectives.push(JSON.parse(JSON.stringify(e)));
            },
            "CHAT_MESSAGE_AEGIS_STOLEN": function(e)
            {
                e.slot = e.player1;
                parsed_data.objectives.push(JSON.parse(JSON.stringify(e)));
            },
            "CHAT_MESSAGE_AEGIS_DENIED": function(e)
            {
                //aegis (player)
                //player1 = slot who picked up/denied/stole aegis
                e.slot = e.player1;
                parsed_data.objectives.push(JSON.parse(JSON.stringify(e)));
            },
            "CHAT_MESSAGE_ROSHAN_KILL": function(e)
            {
                //player1 = team that killed roshan? (2/3)
                e.team = e.player1;
                parsed_data.objectives.push(JSON.parse(JSON.stringify(e)));
            },
            //CHAT_MESSAGE_UNPAUSED = 36;
            //CHAT_MESSAGE_COURIER_LOST = 10;
            //CHAT_MESSAGE_COURIER_RESPAWNED = 11;
            //"CHAT_MESSAGE_SUPER_CREEPS"
            //"CHAT_MESSAGE_HERO_DENY"
            //"CHAT_MESSAGE_STREAK_KILL"
            //"CHAT_MESSAGE_BUYBACK"
            "chat": function getChatSlot(e)
            {
                //e.slot = name_to_slot[e.unit];
                //push a copy to chat
                parsed_data.chat.push(JSON.parse(JSON.stringify(e)));
            },
            "interval": function(e)
            {
                if (e.time >= 0)
                {
                    var e2 = JSON.parse(JSON.stringify(e));
                    e2.type = "stuns";
                    e2.value = e2.stuns;
                    populate(e2);
                    //if on minute, add to lh/gold/xp
                    if (e.time % 60 === 0)
                    {
                        var e3 = JSON.parse(JSON.stringify(e));
                        e3.interval = true;
                        e3.type = "times";
                        e3.value = e3.time;
                        populate(e3);
                        e3.type = "gold_t";
                        e3.value = e3.gold;
                        populate(e3);
                        e3.type = "xp_t";
                        e3.value = e3.xp;
                        populate(e3);
                        e3.type = "lh_t";
                        e3.value = e3.lh;
                        populate(e3);
                    }
                    //add to positions
                    //not currently storing pos data
                    //make a copy if mutating
                    // if (e.x && e.y) {
                    //     e.type = "pos";
                    //     e.key = [e.x, e.y];
                    //     e.posData = true;
                    //     //populate(e);
                    // }
                }
                // store player position for the first 10 minutes
                if (e.time <= 600 && e.x && e.y)
                {
                    var e4 = JSON.parse(JSON.stringify(e));
                    e4.type = "lane_pos";
                    e4.key = [e4.x, e4.y];
                    e4.posData = true;
                    populate(e4);
                }
            },
            "obs": function(e)
            {
                //key is a JSON array of position data
                e.key = JSON.parse(e.key);
                e.posData = true;
                populate(e);
                e.posData = false;
                e.type = "obs_log";
                populate(e);
            },
            "sen": function(e)
            {
                e.key = JSON.parse(e.key);
                e.posData = true;
                populate(e);
                e.posData = false;
                e.type = "sen_log";
                populate(e);
            }
            
                //strips off "item_" from strings
    function translate(input)
    {
        if (input != null)
        {
            if (input.indexOf("item_") === 0)
            {
                input = input.slice(5);
            }
        }
        return input;
    }
    //prepends illusion_ to string if illusion
    function computeIllusionString(input, isIllusion)
    {
        return (isIllusion ? "illusion_" : "") + input;
    }