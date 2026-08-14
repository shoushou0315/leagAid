package com.example.demo.model;

import java.util.List;

/**
 * 游戏实时快照（对应 Redis leagaid:state 的 JSON 结构）
 */
public class GameState {
    private String phase;
    private String myChampion;
    private String myPuuid;
    private String currentGameId;
    private String sessionId;
    private List<GamePlayer> players;
    private List<String> bench;
    private List<HexOption> hexOptions;
    private long updatedAt;

    public String getPhase() { return phase; }
    public void setPhase(String phase) { this.phase = phase; }
    public String getMyChampion() { return myChampion; }
    public void setMyChampion(String myChampion) { this.myChampion = myChampion; }
    public String getMyPuuid() { return myPuuid; }
    public void setMyPuuid(String myPuuid) { this.myPuuid = myPuuid; }
    public String getCurrentGameId() { return currentGameId; }
    public void setCurrentGameId(String currentGameId) { this.currentGameId = currentGameId; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public List<GamePlayer> getPlayers() { return players; }
    public void setPlayers(List<GamePlayer> players) { this.players = players; }
    public List<String> getBench() { return bench; }
    public void setBench(List<String> bench) { this.bench = bench; }
    public List<HexOption> getHexOptions() { return hexOptions; }
    public void setHexOptions(List<HexOption> hexOptions) { this.hexOptions = hexOptions; }
    public long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(long updatedAt) { this.updatedAt = updatedAt; }

    /** 玩家：账号层 + 实时层 */
    public static class GamePlayer {
        private String puuid;
        private String name;
        private String team;
        private String champion;
        private String mastery;
        private int games;
        private double winRate;
        private String kda;
        private String style;
        private int level;
        private int kills;
        private int deaths;
        private int assists;
        private String items;

        public String getPuuid() { return puuid; }
        public void setPuuid(String puuid) { this.puuid = puuid; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getTeam() { return team; }
        public void setTeam(String team) { this.team = team; }
        public String getChampion() { return champion; }
        public void setChampion(String champion) { this.champion = champion; }
        public String getMastery() { return mastery; }
        public void setMastery(String mastery) { this.mastery = mastery; }
        public int getGames() { return games; }
        public void setGames(int games) { this.games = games; }
        public double getWinRate() { return winRate; }
        public void setWinRate(double winRate) { this.winRate = winRate; }
        public String getKda() { return kda; }
        public void setKda(String kda) { this.kda = kda; }
        public String getStyle() { return style; }
        public void setStyle(String style) { this.style = style; }
        public int getLevel() { return level; }
        public void setLevel(int level) { this.level = level; }
        public int getKills() { return kills; }
        public void setKills(int kills) { this.kills = kills; }
        public int getDeaths() { return deaths; }
        public void setDeaths(int deaths) { this.deaths = deaths; }
        public int getAssists() { return assists; }
        public void setAssists(int assists) { this.assists = assists; }
        public String getItems() { return items; }
        public void setItems(String items) { this.items = items; }
    }

    /** 海克斯选项 */
    public static class HexOption {
        private String name;
        private int slot;
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public int getSlot() { return slot; }
        public void setSlot(int slot) { this.slot = slot; }
    }
}
