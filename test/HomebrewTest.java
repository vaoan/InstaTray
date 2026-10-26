public class HomebrewTest {
    public static void main(String[] args) {
        String json = """
                {"tag_name":"7.0.10","assets":[
                  {"name":"Homebrew-7.0.10.tar.gz","browser_download_url": "https://github.com/Homebrew/brew/releases/download/7.0.10/Homebrew-7.0.10.tar.gz"},
                  {"name":"Homebrew.pkg","browser_download_url": "https://github.com/Homebrew/brew/releases/download/7.0.10/Homebrew.pkg"}]}""";
        T.eq("pkgUrlFound", "https://github.com/Homebrew/brew/releases/download/7.0.10/Homebrew.pkg", InstaTray.homebrewPkgUrl(json));
        T.eq("pkgUrlMissing", null, InstaTray.homebrewPkgUrl("{\"assets\":[]}"));
        T.eq("pkgUrlOnlyGithub", null, InstaTray.homebrewPkgUrl(
                "{\"browser_download_url\": \"https://evil.example/Homebrew.pkg\"}"));
        T.done();
    }
}
