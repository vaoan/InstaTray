package instatray.core;

/** Why a download that ended without files failed, from the tool's output, and whether retrying could help. */
final class Failure {

    private Failure() {
    }

    /**
     * @param exit the tool's exit code
     * @param tail the last output line
     * @param all  the output (the last lines of it)
     */
    static Fetch.Result classify(Site site, int exit, String tail, String all, String title) {
        boolean gallery = site.engine() == Engine.GALLERY_DL;
        String reason;
        if (gallery && exit == 0) {
            reason = Fetch.NO_VIDEO_IN_POST;
        } else if (site == Site.PIXIV && (all.contains("refresh-token") || all.contains("401")
                || all.contains("Unauthorized") || all.contains("OAuth"))) {
            reason = Fetch.PIXIV_LOGIN;
        } else if (gallery && (all.contains("403") || all.contains("401") || all.contains("login")
                || all.contains("Forbidden") || all.contains("cookies"))) {
            reason = Fetch.LOGIN_NEEDED;
        } else if (all.contains("anti-bot challenge")) {
            reason = Fetch.ANTI_BOT; // retrying never helps
        } else if (site == Site.EMBED && (all.contains("HTTP Error 403") || all.contains("Forbidden"))) {
            reason = Fetch.PAGE_ONLY; // the server refuses anything but its own player: no workaround
        } else if (site == Site.EMBED && (tail.contains("Unsupported URL") || all.contains("404"))) {
            reason = Fetch.NO_VIDEO_ON_PAGE;
        } else if (all.contains("404") || all.contains("NotFound") || all.contains("not found")) {
            reason = Fetch.NOT_FOUND;
        } else if (exit == 0 || tail.contains("is_live")) {
            reason = Fetch.LIVE;
        } else if (tail.contains("There is no video") || tail.contains("No video could be found")) {
            reason = Fetch.NO_VIDEO;
        } else if ((tail.contains("login") || tail.contains("cookies") || tail.contains("Sign in"))
                && !tail.contains("rate-limit")) {
            reason = Fetch.LOGIN_NEEDED;
        } else { // network, rate limits, server errors: worth retrying
            return new Fetch.Result(Fetch.Outcome.FAILED,
                    tail.startsWith("ERROR: ") ? tail.substring("ERROR: ".length()) : tail, java.util.List.of(), title);
        }
        return new Fetch.Result(Fetch.Outcome.FAILED_PERMANENT, reason, java.util.List.of(), title);
    }
}
