package dev.nasbackup;
final class Schedule {
    static boolean due(long now,long lastSuccess,String saved,String current,long stableSince,long retryAfter){
        return now>=retryAfter&&now-stableSince>=60000&&(!current.equals(saved)||now-lastSuccess>=86400000||now<lastSuccess);
    }
}
