var config = require('./config');
var redis = require('./redis');
var zlib = require('zlib');
var compute = require('./compute');
var computePlayerMatchData = compute.computePlayerMatchData;
var aggregator = require('./aggregator');
var utility = require('./utility');
var reduceMatch = utility.reduceMatch;
var async = require('async');
var constants = require('./constants');

function readCache(account_id, cb)
{
    if (config.ENABLE_PLAYER_CACHE)
    {
        console.time('readcache');
        redis.get(new Buffer("player:" + account_id), function(err, result)
        {
            var cache = result ? JSON.parse(zlib.inflateSync(result)) : null;
            if (cache && cache.data)
            {
                //unpack cache.data into an array
                var arr = [];
                for (var key in cache.data)
                {
                    arr.push(cache.data[key]);
                }
                cache.data = arr;
            }
            console.timeEnd('readcache');
            return cb(err, cache);
        });
    }
    else
    {
        return cb();
    }
}

function writeCache(account_id, data, aggData, cb)
{
    if (config.ENABLE_PLAYER_CACHE)
    {
        console.time("writecache");
        //pack data into hash for cache
        var match_ids = {};
        data.forEach(function(m)
        {
            var identifier = [m.match_id, m.player_slot].join(':');
            match_ids[identifier] = m;
        });
        var cache = {
            data: match_ids,
            aggData: aggData
        };
        //console.log(Object.keys(cache.data).length);
        console.log("saving player cache %s", account_id);
        redis.setex(new Buffer("player:" + account_id), 60 * 60 * 24 * config.UNTRACK_DAYS, zlib.deflateSync(JSON.stringify(cache)));
        console.timeEnd("writecache");
        return cb();
    }
    else
    {
        return cb();
    }
}

function updateCache(match, cb)
{
    if (config.ENABLE_PLAYER_CACHE)
    {
        var players = match.players;
        if (match.pgroup && players)
        {
            players.forEach(function(p)
            {
                //add account id to each player so we know what caches to update
                p.account_id = match.pgroup[p.player_slot].account_id;
                //add hero_id to each player so we update records with hero played
                p.hero_id = match.pgroup[p.player_slot].hero_id;
            });
        }
        async.eachSeries(players, function(player_match, cb)
        {
            if (player_match.account_id && player_match.account_id !== constants.anonymous_account_id)
            {
                //join player with match to form player_match
                for (var key in match)
                {
                    player_match[key] = match[key];
                }
                redis.get(new Buffer("player:" + player_match.account_id), function(err, result)
                {
                    if (err)
                    {
                        return cb(err);
                    }
                    var cache = result ? JSON.parse(zlib.inflateSync(result)) : null;
                    //if player cache doesn't exist, skip
                    if (cache)
                    {
                        var reInsert = player_match.match_id in cache.aggData.match_ids && player_match.insert_type === "api";
                        var reParse = player_match.match_id in cache.aggData.parsed_match_ids && player_match.insert_type === "parsed";
                        if (!reInsert && !reParse)
                        {
                            computePlayerMatchData(player_match);
                            cache.aggData = aggregator([player_match], player_match.insert_type, cache.aggData);
                        }
                        //reduce match to save cache space--we only need basic data per match for matches tab
                        var reduced_player_match = reduceMatch(player_match);
                        var identifier = [player_match.match_id, player_match.player_slot].join(':');
                        var orig = cache.data[identifier];
                        if (!orig)
                        {
                            cache.data[identifier] = reduced_player_match;
                        }
                        else
                        {
                            //iterate instead of setting directly to avoid clobbering existing data
                            for (var key in reduced_player_match)
                            {
                                orig[key] = reduced_player_match[key] || orig[key];
                            }
                        }
                        redis.ttl("player:" + player_match.account_id, function(err, ttl)
                        {
                            if (err)
                            {
                                return cb(err);
                            }
                            redis.setex(new Buffer("player:" + player_match.account_id), Number(ttl) > 0 ? Number(ttl) : 24 * 60 * 60 * config.UNTRACK_DAYS, zlib.deflateSync(JSON.stringify(cache)));
                            cb(err);
                        });
                    }
                    else
                    {
                        return cb();
                    }
                });
            }
            else
            {
                return cb();
            }
        }, cb);
    }
    else
    {
        return cb();
    }
}
module.exports = {
    readCache: readCache,
    writeCache: writeCache,
    updateCache: updateCache
};