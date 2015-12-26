var request = require('request');
var cp = require('child_process');
var utility = require('./utility');
var ndjson = require('ndjson');
var spawn = cp.spawn;
var progress = require('request-progress');
module.exports = function runParse(match, cb)
{
    var print_multi_kill_streak_debugging = false;
    var url = match.url;
    var inStream;
    var parseStream;
    var bz;
    var parser;
    //parse state
    var entries = [];
    var parsed_data = null;
    inStream = progress(request(
    {
        url: url,
        encoding: null,
        timeout: 30000
    })).on('progress', function(state)
    {
        console.log(JSON.stringify(
        {
            url: url,
            percent: state.percent
        }));
    }).on('response', function(response)
    {
        if (response.statusCode === 200)
        {
            parser = spawn("java", ["-jar",
                    "-Xmx64m",
                    "java_parser/target/stats-0.1.0.jar"
                ],
            {
                //we may want to ignore stderr so the child doesn't stay open
                stdio: ['pipe', 'pipe', 'pipe'],
                encoding: 'utf8'
            });
            parseStream = ndjson.parse();
            if (url.slice(-3) === "bz2")
            {
                bz = spawn("bunzip2");
                inStream.pipe(bz.stdin);
                bz.stdout.pipe(parser.stdin);
            }
            else
            {
                inStream.pipe(parser.stdin);
            }
            parser.stdout.pipe(parseStream);
            parser.stderr.on('data', function(data)
            {
                console.log(data.toString());
            });
            parseStream.on('data', handleStream);
            parseStream.on('end', exit);
            parseStream.on('error', exit);
        }
        else
        {
            exit(response.statusCode.toString());
        }
    }).on('error', exit);

    function exit(err)
    {
        if (!err)
        {
            parsed_data = utility.getParseSchema();
            var message = "time spent on post-processing match ";
            console.time(message);
            processTeamfights();
            processAllPlayers();
            processReduce();
            //processMultiKillStreaks();
            console.timeEnd(message);
        }
        return cb(err, parsed_data);
    }
    //callback when the JSON stream encounters a JSON object (event)
    function handleStream(e)
    {
        if (e.type === "player_slot")
        {
            parsed_data.players[e.key].player_slot = e.value;
        }
        populate(e);
        entries.push(e);
    }

    function populate(e, container)
    {
        //set slot and player_slot
        e.slot = e.player_slot % (128 - 5);
        //use parsed_data by default if nothing passed in
        container = container || parsed_data;
        if (!container.players[e.slot])
        {
            //couldn't associate with a player, probably attributed to a creep/tower/necro unit
            //console.log(e);
            return;
        }
        var t = container.players[e.slot][e.type];
        if (typeof t === "undefined")
        {
            //container.players[0] doesn't have a type for this event
            console.log("no field in parsed_data.players for %s", JSON.stringify(e));
            return;
        }
        else if (e.posData)
        {
            //fill 2d hash with x,y values
            var x = e.key[0];
            var y = e.key[1];
            if (!t[x])
            {
                t[x] = {};
            }
            if (!t[x][y])
            {
                t[x][y] = 0;
            }
            t[x][y] += 1;
        }
        else if (e.max)
        {
            //check if value is greater than what was stored
            if (e.value > t.value)
            {
                container.players[e.slot][e.type] = e;
            }
        }
        else if (t.constructor === Array)
        {
            //determine whether we want the value only (interval) or the time and key (log)
            //either way this creates a new value so e can be mutated later
            var arrEntry = (e.interval) ? e.value :
            {
                time: e.time,
                key: e.key
            };
            t.push(arrEntry);
        }
        else if (typeof t === "object")
        {
            //add it to hash of counts
            e.value = e.value || 1;
            t[e.key] ? t[e.key] += e.value : t[e.key] = e.value;
            if (print_multi_kill_streak_debugging)
            {
                if (e.type == "kill_streaks")
                {
                    console.log("\t%s got a kill streak of %s", e.unit, e.key);
                }
                else if (e.type == "multi_kills")
                {
                    console.log("\t%s got a multi kill of %s", e.unit, e.key);
                }
            }
        }
        else if (typeof t === "string")
        {
            //string, used for steam id
            container.players[e.slot][e.type] = e.key;
        }
        else
        {
            //we must use the full reference since this is a primitive type
            //use the value most of the time, but key when stuns since value only holds Integers in Java
            //replace the value directly
            container.players[e.slot][e.type] = e.value || Number(e.key);
        }
    }
    //Group events in buffer
    function processReduce()
    {
        var reduceMap = {};
        //group by player_slot, type, key
        for (var i = 0; i < entries.length; i++)
        {
            var e = entries[i];
            var identifier = [e.player_slot, e.type, e.key].join(":");
            e.value = e.value || 1;
            reduceMap[identifier] = reduceMap[identifier] ? reduceMap[identifier] + e.value : e.value || 1;
        }
        console.log(reduceMap);
        //TODO dump out grouped results to replace originals
    }
    //Compute data requiring all players in a match for storage in match table
    function processAllPlayers()
    {
        var goldAdvTime = {};
        var xpAdvTime = {};
        for (var i = 0; i < entries.length; i++)
        {
            var e = entries[i];
            if (e.type === "gold_t" && e.time % 60 === 0)
            {
                var g = utility.isRadiant(
                {
                    player_slot: e.player_slot
                }) ? e.gold : -e.gold;
                goldAdvTime[e.time] = goldAdvTime[e.time] ? goldAdvTime[e.time] + g : g;
            }
            if (e.type === "xp_t" && e.time % 60 === 0)
            {
                var x = utility.isRadiant(
                {
                    player_slot: e.player_slot
                }) ? e.xp : -e.xp;
                xpAdvTime[e.time] = xpAdvTime[e.time] ? xpAdvTime[e.time] + x : x;
            }
        }
        var order = Object.keys(goldAdvTime).sort(function(a, b)
        {
            return Number(a) - Number(b);
        });
        order.forEach(function(k)
        {
            parsed_data.radiant_gold_adv.push(goldAdvTime[k]);
            parsed_data.radiant_xp_adv.push(xpAdvTime[k]);
        });
    }
    //Compute teamfights that occurred
    function processTeamfights()
    {
        var curr_teamfight;
        var teamfights = [];
        var teamfight_cooldown = 15;
        //fights that didnt end wont be pushed to teamfights array (endgame case)
        //filter only fights where 3+ heroes died
        for (var i = 0; i < entries.length; i++)
        {
            var e = entries[i];
            var intervalState = {
                "pos":
                {},
                "xp":
                {}
            };
            //store hero state at each interval for teamfight lookup
            if (e.type === "pos" || e.type === "xp")
            {
                var typeState = intervalState[e.type];
                if (!typeState[e.time])
                {
                    typeState[e.time] = {};
                }
                typeState[e.time][e.slot] = e;
                //check curr_teamfight status
                if (curr_teamfight && e.time - curr_teamfight.last_death >= teamfight_cooldown)
                {
                    //close it
                    curr_teamfight.end = e.time;
                    //push a copy for post-processing
                    teamfights.push(JSON.parse(JSON.stringify(curr_teamfight)));
                    //clear existing teamfight
                    curr_teamfight = null;
                }
            }
            else if (e.type === "killed" && e.key.indexOf("illusion_") !== 0 && e.key.indexOf("npc_dota_hero") !== -1)
            {
                //check teamfight state
                curr_teamfight = curr_teamfight ||
                {
                    start: e.time - teamfight_cooldown,
                    end: null,
                    last_death: e.time,
                    deaths: 0,
                    players: Array.apply(null, new Array(parsed_data.players.length)).map(function()
                    {
                        return {
                            deaths_pos:
                            {},
                            ability_uses:
                            {},
                            item_uses:
                            {},
                            killed:
                            {},
                            deaths: 0,
                            buybacks: 0,
                            damage: 0,
                            gold_delta: 0,
                            xp_delta: 0
                        };
                    })
                };
                //update the last_death time of the current fight
                curr_teamfight.last_death = e.time;
                curr_teamfight.deaths += 1;
            }
        }
        teamfights = teamfights.filter(function(tf)
        {
            return tf.deaths >= 3;
        });
        teamfights.forEach(function(tf)
        {
            tf.players.forEach(function(p, ind)
            {
                //record player's start/end xp for level change computation
                if (intervalState.xp[tf.start] && intervalState.xp[tf.end])
                {
                    p.xp_start = intervalState[tf.start][ind].xp;
                    p.xp_end = intervalState[tf.end][ind].xp;
                }
            });
        });
        for (var i = 0; i < entries.length; i++)
        {
            //loop over entries again
            var e = entries[i];
            //check each teamfight to see if this event should be processed as part of that teamfight
            for (var j = 0; j < teamfights.length; j++)
            {
                var tf = teamfights[j];
                if (e.time >= tf.start && e.time <= tf.end)
                {
                    if (e.type === "killed" && e.key.indexOf("illusion_") !== 0 && e.key.indexOf("npc_dota_hero") !== -1)
                    {
                        populate(e, tf);
                    }
                    if (e.type === "killed_by")
                    {
                        //increment death count for this hero
                        tf.players[e.slot].deaths += 1;
                        if (intervalState[e.time][e.slot])
                        {
                            //if a hero dies, add to deaths_pos, lookup slot of the killed hero by hero name (e.key), get position from intervalstate
                            var x = intervalState[e.time][e.slot].x;
                            var y = intervalState[e.time][e.slot].y;
                            //fill in the copy
                            e.type = "deaths_pos";
                            e.key = [x, y];
                            e.posData = true;
                            populate(e, tf);
                        }
                    }
                    else if (e.type === "buyback_log")
                    {
                        //bought back
                        if (tf.players[e.slot])
                        {
                            tf.players[e.slot].buybacks += 1;
                        }
                    }
                    else if (e.type === "damage")
                    {
                        //sum damage
                        //check if damage dealt to hero and not illusion
                        if (e.targethero && !e.targetillusion)
                        {
                            //check if the damage dealer could be assigned to a slot
                            if (tf.players[e.slot])
                            {
                                tf.players[e.slot].damage += e.value;
                            }
                        }
                    }
                    else if (e.type === "gold_reasons" || e.type === "xp_reasons")
                    {
                        //add gold/xp to delta
                        if (tf.players[e.slot])
                        {
                            var types = {
                                "gold_reasons": "gold_delta",
                                "xp_reasons": "xp_delta"
                            };
                            tf.players[e.slot][types[e.type]] += e.value;
                        }
                    }
                    else if (e.type === "ability_uses" || e.type === "item_uses")
                    {
                        var e2 = JSON.parse(JSON.stringify(e));
                        //count skills, items
                        populate(e2, tf);
                    }
                    else
                    {
                        continue;
                    }
                }
            }
        }
        parsed_data.teamfights = teamfights;
    }
    /*
    // associate kill streaks with multi kills and team fights
    function processMultiKillStreaks()
    {
        // bookkeeping about each player
        var players = {};
        // for each entry in the combat log
        for (var i = 0; i < entries.length; i++)
        {
            var entry = entries[i];
            // identify the killer
            var killer = entry.unit;
            var killer_index = hero_to_slot[killer];
            // if the killer is a hero (which it might not be)
            if (killer_index !== undefined)
            {
                // bookmark this player's parsed bookkeeping
                var parsed_info = parsed_data.players[killer_index];
                // if needed, initialize this player's bookkeeping
                if (players[killer_index] === undefined)
                {
                    parsed_info.kill_streaks_log.push([]);
                    players[killer_index] = {
                        "cur_multi_id": 0, // the id of the current multi kill
                        "cur_multi_val": 0, // the value of the current multi kill
                        "cur_streak_budget": 2 // the max length of the current kill streak
                    };
                }
                // get the number of streaks and the length of the current streak
                var all_streak_length = parsed_info.kill_streaks_log.length;
                var cur_streak_length = parsed_info.kill_streaks_log[all_streak_length - 1].length;
                // bookmark this player's local bookkeeping
                var local_info = players[killer_index];
                // if this entry is a valid kill notification
                if (entry.type === "killed" && entry.targethero && !entry.targetillusion)
                {
                    // determine who was killed
                    var killed = entry.key;
                    var killed_index = hero_to_slot[killed];
                    // if this is a valid kill (note: self-denies (via bloodstone, etc) are logged
                    // as kill events but do not break kill streaks or multi kills events)
                    if (killer_index != killed_index)
                    {
                        // check if we've run out of room in the current kills array (note: this
                        // would happen because (for some reason) the combat log does not contain
                        // kill streak events for streaks of size 2 (even though it really should))
                        var cur_streak_budget = local_info.cur_streak_budget;
                        if (cur_streak_length == cur_streak_budget && cur_streak_budget == 2)
                        {
                            // remove the first element of the streak (note: later we will
                            // push a new second element on to the end of the streak)
                            parsed_info.kill_streaks_log[all_streak_length - 1].splice(0, 1);
                            cur_streak_length--;
                            // check if the current kill streak has ended
                        }
                        else if (cur_streak_length >= cur_streak_budget)
                        {
                            // if so, create a new streak in the kills array
                            all_streak_length++;
                            cur_streak_length = 0;
                            parsed_info.kill_streaks_log.push([]);
                            local_info.cur_streak_budget = 2;
                            if (print_multi_kill_streak_debugging)
                            {
                                console.log("\t%s kill streak has ended", killer);
                            }
                        }
                        // check if the current multi kill has ended
                        if (local_info.cur_multi_val < parsed_info.multi_kill_id_vals[local_info.cur_multi_id])
                        {
                            // if not, increment the current multi kill value
                            local_info.cur_multi_val++;
                        }
                        else
                        {
                            // if so, create a new multi kill id and value
                            local_info.cur_multi_id++;
                            local_info.cur_multi_val = 1;
                            parsed_info.multi_kill_id_vals.push(1);
                            if (print_multi_kill_streak_debugging)
                            {
                                console.log("\t%s multi kill has ended", killer);
                            }
                        }
                        // determine if this kill was part of a team fight
                        var team_fight_id = 0;
                        var kill_time = entry.time;
                        for (var j = 0; j < teamfights.length; j++)
                        {
                            var teamfight = teamfights[j];
                            if (kill_time >= teamfight.start && kill_time <= teamfight.end)
                            {
                                team_fight_id = j + 1;
                            }
                        }
                        // add this kill to the killer's list of kills
                        parsed_info.kill_streaks_log[all_streak_length - 1].push(
                        {
                            "hero_id": hero_to_id[killed],
                            "multi_kill_id": local_info.cur_multi_id,
                            "team_fight_id": team_fight_id,
                            "time": kill_time
                        });
                        if (print_multi_kill_streak_debugging)
                        {
                            console.log("\t%s killed %s", killer, killed);
                        }
                    }
                    // if this entry is a notification of a multi kill (note: the kill that caused
                    // this multi kill has not been seen yet; it will one of the next few entries)
                }
                else if (entry.type === "multi_kills")
                {
                    // update the value of the current multi kill
                    parsed_info.multi_kill_id_vals[local_info.cur_multi_id] = parseInt(entry.key);
                    if (print_multi_kill_streak_debugging)
                    {
                        console.log("\t%s got a multi kill of %s", killer, entry.key);
                    }
                    // if this entry is a notification of a kill streak (note: the kill that caused
                    // this kill streak has not been seen yet; it will one of the next few entries)
                }
                else if (entry.type === "kill_streaks")
                {
                    // update the value of the current kill streak
                    local_info.cur_streak_budget = parseInt(entry.key);
                    if (print_multi_kill_streak_debugging)
                    {
                        console.log("\t%s got a kill streak of %s", killer, entry.key);
                    }
                }
            }
        }
        // remove small (length < 3) kill streaks
        for (var index in players)
        {
            var data = parsed_data.players[index].kill_streaks_log;
            var i = data.length;
            while (i--)
            {
                if (data[i].length < 3)
                {
                    data.splice(i, 1);
                }
            }
        }
    }
    */
}
