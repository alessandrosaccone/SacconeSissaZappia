package org.SacconeSissaZappia.GitHubIntegrationMicroservice.Controller;

import org.SacconeSissaZappia.GitHubIntegrationMicroservice.Download.Downloader;
import org.SacconeSissaZappia.GitHubIntegrationMicroservice.Git.GitOp;
import org.SacconeSissaZappia.GitHubIntegrationMicroservice.GitHubIntegrationMain;
import org.SacconeSissaZappia.GitHubIntegrationMicroservice.UnzipUtil.UnzipUtil;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.transport.URIish;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;

/***
 * Controller class for the GitHubIntegrationMicroservice, which is responsible for
 * receiving all the requests which require some kind of interaction with the GitHub rest API and
 * handle them.
 * More specifically, this controller manages the creation of GitHub repositories when a battle starts and
 * it coordinates the process for calculating the new score of a student's solution when a commit is performed on GitHub.
 */

@RestController
public class Controller {

    @Value("${githubDownloader.PersonalAccessToken}")
    private String personalAccessToken;

    @Autowired
    private GitOp git;

    @Autowired
    private Downloader downlaoder;



    /***
     * This API method is called by the Gateway Microservice Controller whenever a notification of
     * a new commit arrives from GitHub. This method is the entry point for the entire computation process of the score
     * associated to the new solution uploaded on GitHub.
     * @param username
     * @param repository
     * @return
     */
    @GetMapping("/newSubmission/{username}/{repository}")
    public void downloadNewCode(@PathVariable String username, @PathVariable String repository,
                            @RequestParam String tour, @RequestParam String battle, @RequestParam String teamName) throws URISyntaxException, IOException, InterruptedException {

        /* Download the code from the remote repository */
        URI internalServerPath = new URI(String.format("/repos/%s/%s/zipball", username, repository));
        System.out.println(internalServerPath);
        downlaoder.download(internalServerPath, Path.of(username+ ".zip"));

        /* Unzip the content of the directory */
        Path localRepoPath = GitHubIntegrationMain.BASE_DIR.resolve(Path.of("github_integration_microservice","code_download", username + ".zip"));
        System.out.println(localRepoPath);
        UnzipUtil.unzipFileFromTo(localRepoPath, null);
        /* Rename directory with username of the committer */
        UnzipUtil.renameDirectory(localRepoPath.getParent(), username, username);
        /* Remove zip file */
        localRepoPath.toFile().delete();
        /* Remove any target folder from the repository (if the user built the project) */
        Path pathToTarget = localRepoPath.getParent().resolve(Path.of(username, "CODE", "target"));
        System.out.println("The target repository path is: " + pathToTarget.toString());
        UnzipUtil.removeFolderRecursively(pathToTarget);

        /* Contact the Score Computation Microservice to calculate the new score and update it in the DB */
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .GET()
                .uri(new URI(String.format("http://localhost:8089/computeScore/%s?tour=%s&battle=%s&teamName=%s",
                        username, tour, battle, teamName)))
                .build();
        /* Sent synchronously because it is necessary to wait until the response in order to then remove the folder from the download folder */
        client.send(request, HttpResponse.BodyHandlers.discarding());

        System.out.println("Everything complete, waiting to remove folder...");
        Thread.sleep(5000);
        UnzipUtil.removeFolderRecursively(localRepoPath.getParent().resolve(username));
        System.out.println("Folder removed");


    }

    @GetMapping("/publishRepo/{tournament}/{battle}")
    public void createGitHubRepo(@PathVariable String tournament, @PathVariable String battle) throws URISyntaxException, IOException, InterruptedException, GitAPIException {
        /* Create a new local repository for the battle to be created (inside the dedicated local folder "battles" */
        Path localPathToBattle = GitHubIntegrationMain.BASE_DIR.resolve(Path.of("battles", battle));
        localPathToBattle.toFile().mkdirs();
        System.out.println("Created local directory on the path: " + localPathToBattle);

        /* Get the build automation scripts and test cases from the battle microservice (through API calls) */
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest testCasesRequest = HttpRequest.newBuilder()
                .GET().uri(new URI(String.format("http://localhost:8083/getTests/%s/%s", tournament, battle)))
                .build();
        HttpRequest buildAutomationScriptsRequest = HttpRequest.newBuilder()
                .GET().uri(new URI(String.format("http://localhost:8083/getScripts/%s/%s", tournament, battle)))
                .build();
        HttpResponse<Path> getTests = client.send(testCasesRequest, HttpResponse.BodyHandlers.ofFile(localPathToBattle.resolve("tests.zip")));
        System.out.println("Retrieved tests from the database for the battle: " + battle);
        HttpResponse<Path> getScripts = client.send(buildAutomationScriptsRequest, HttpResponse.BodyHandlers.ofFile(localPathToBattle.resolve("scripts.zip")));
        System.out.println("Retrieved scripts from the database for the battle: " + battle);

        /* Extract the two zip files */
        System.out.println("Unzipping both tests and scripts for the battle: "+ battle);
        UnzipUtil.unzipFileFromTo(localPathToBattle.resolve("tests.zip"), null);
        UnzipUtil.unzipFileFromTo(localPathToBattle.resolve("scripts.zip"), null);


        /* Remove zip files */
        System.out.println("Eliminating the zip files...");
        localPathToBattle.resolve("tests.zip").toFile().delete();
        localPathToBattle.resolve("scripts.zip").toFile().delete();


        /* Create the empty directory on Github with the name of the battle */
        System.out.println("Sending Github the request to create a new empty repository for the battle: " + battle);
        HttpRequest newRepo = HttpRequest.newBuilder()
                .POST(HttpRequest.BodyPublishers.ofString(String.format("{ \"name\": \"%s\" }", battle)))
                .uri(new URI(String.format("https://api.github.com/user/repos")))
                .header("Authorization", "Bearer " + personalAccessToken)
                .header("Content-Type", "application/json")
                .build();
        HttpResponse<String> response = client.send(newRepo, HttpResponse.BodyHandlers.ofString());
        System.out.println("The response from GitHub was: " + response.body());

        /* Associate local repository to remote repository and push all the content */
        System.out.println("Starting git operations to push all the content of the repository for the battle: " + battle);
        URIish uri = new URIish(String.format("https://github.com/ckbGitHub/%s.git", battle));
        git.performGitOperations(localPathToBattle, uri);
    }





}
