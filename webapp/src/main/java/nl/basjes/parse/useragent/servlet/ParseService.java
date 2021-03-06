/*
 * Yet Another UserAgent Analyzer
 * Copyright (C) 2013-2019 Niels Basjes
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package nl.basjes.parse.useragent.servlet;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import nl.basjes.parse.useragent.UserAgent;
import nl.basjes.parse.useragent.UserAgentAnalyzer;
import nl.basjes.parse.useragent.Version;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.MutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import javax.annotation.PostConstruct;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static nl.basjes.parse.useragent.utils.YauaaVersion.getVersion;
import static org.apache.commons.text.StringEscapeUtils.escapeHtml4;
import static org.springframework.http.MediaType.APPLICATION_JSON_VALUE;
import static org.springframework.http.MediaType.APPLICATION_XML_VALUE;
import static org.springframework.http.MediaType.TEXT_PLAIN_VALUE;

@SuppressWarnings("deprecation") // The description field may be deprecated but in SpringFox it is still used.
@Api(tags = "Yauaa", description = "Useragent parsing service")
@SpringBootApplication
@RestController
public class ParseService {

    private static       UserAgentAnalyzer userAgentAnalyzer               = null;
    private static       boolean           isInitializing                  = false;
    private static       long              initStartMoment;
    private static       boolean           userAgentAnalyzerIsAvailable    = false;
    private static       String            userAgentAnalyzerFailureMessage = null;
    private static final String            ANALYZER_VERSION                = getVersion();
    private static final String            API_BASE_PATH                   = "/yauaa/v1";

    private static final String            EXAMPLE_USERAGENT               =
        "Mozilla/5.0 (Linux; Android 7.0; Nexus 6 Build/NBD90Z) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/53.0.2785.124 Mobile Safari/537.36";

    private enum OutputType {
        HTML,
        JSON,
        XML
    }

    private static synchronized void ensureStarted() {
        if (!isInitializing && !userAgentAnalyzerIsAvailable && userAgentAnalyzerFailureMessage == null) {
            isInitializing = true;
            initStartMoment = System.currentTimeMillis();
            new Thread(() -> {
                try {
                    userAgentAnalyzer = UserAgentAnalyzer.newBuilder().hideMatcherLoadStats().keepTests().build();
                    userAgentAnalyzer.initializeMatchers();
                    userAgentAnalyzerIsAvailable = true;
                } catch (Exception e) {
                    userAgentAnalyzerFailureMessage =
                        e.getClass().getSimpleName() + "<br/>" +
                        e.getMessage().replaceAll("\n", "<br/>");
                }
                isInitializing = false;
            }).start();
        }
    }

    @PostConstruct
    public void automaticStartup() {
        ensureStarted();
    }

    public static class YauaaIsBusyStarting extends RuntimeException {
        private final OutputType outputType;

        public OutputType getOutputType() {
            return outputType;
        }

        public YauaaIsBusyStarting(OutputType outputType) {
            this.outputType = outputType;
        }
    }

    private static void ensureStartedForApis(OutputType outputType) {
        ensureStarted();
        if (!userAgentAnalyzerIsAvailable) {
            throw new YauaaIsBusyStarting(outputType);
        }
    }

    @ControllerAdvice
    public static class RestResponseEntityExceptionHandler
        extends ResponseEntityExceptionHandler {

        @ExceptionHandler({ YauaaIsBusyStarting.class })
        public ResponseEntity<Object> handleYauaaIsStarting(
            Exception ex,
            @SuppressWarnings("unused") WebRequest request) {
            final HttpHeaders httpHeaders = new HttpHeaders();
            httpHeaders.add("Retry-After", "5"); // Retry after 5 seconds.

            YauaaIsBusyStarting yauaaIsBusyStarting = (YauaaIsBusyStarting) ex;

            long timeSinceStart = System.currentTimeMillis() - initStartMoment;
            String message;

            switch (yauaaIsBusyStarting.getOutputType()) {
                case JSON:
                    message = "{ \"status\": \"Starting\", \"timeInMs\": " + timeSinceStart + " }";
                    break;
                case XML:
                    message = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><status>Starting</status><timeInMs>" + timeSinceStart + "</timeInMs>";
                    break;
                case HTML:
                default:
                    message = "Yauaa has been starting up for " + timeSinceStart + " seconds now.";
                    break;
            }

            return new ResponseEntity<>(message, httpHeaders, HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    private static final long MEGABYTE = 1024L * 1024L;

    public static long bytesToMegabytes(long bytes) {
        return bytes / MEGABYTE;
    }

    private static String getMemoryUsage() {
        // Get the Java runtime
        Runtime runtime = Runtime.getRuntime();
        runtime.gc();
        // Calculate the used memory
        long memory = runtime.totalMemory() - runtime.freeMemory();
        return String.format("Used memory is %d MiB", bytesToMegabytes(memory));
    }

    @ResponseStatus(value = HttpStatus.PRECONDITION_FAILED, reason = "The User-Agent header is missing")
    private static class MissingUserAgentException extends RuntimeException {
    }

    // =============== HTML (Human usable) OUTPUT ===============

    @GetMapping(
        value = "/",
        produces = MediaType.TEXT_HTML_VALUE
    )
    public String getHtml(@RequestHeader("User-Agent") String userAgentString) {
        return doHTML(userAgentString);
    }

    @PostMapping(
        value = "/",
        consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE,
        produces = MediaType.TEXT_HTML_VALUE
    )
    public String getHtmlPOST(@ModelAttribute("useragent") String userAgent) {
        return doHTML(userAgent);
    }

//    @ApiOperation(
//        value = "Analyze the provided User-Agent",
//        notes = "The provided UserAgent is to be a single (url encoded) part of the url"
//    )
//    @GetMapping(
//        value = API_BASE_PATH + "/analyze/{userAgent}",
//        produces = MediaType.TEXT_HTML_VALUE
//    )
//    public String getHtmlPath(@PathVariable String userAgent) {
//        return doHTML(userAgent);
//    }

    // =============== JSON OUTPUT ===============

    @ApiOperation(
        value = "Analyze the provided User-Agent",
        notes = "<b>Trying this in swagger does not work in Chrome as Chrome does not allow setting " +
                "a different User-Agent: https://github.com/swagger-api/swagger-ui/issues/5035</b>"
    )
    @GetMapping(
        value = API_BASE_PATH + "/analyze",
        produces = APPLICATION_JSON_VALUE
    )
    public String getJSonGET(
        @ApiParam(
            value = "The standard browser request header User-Agent is used as the input that is to be analyzed.",
            example = EXAMPLE_USERAGENT
        )
        @RequestHeader("User-Agent")
        String userAgentString
    ) {
        return doJSon(userAgentString);
    }

    // -------------------------------------------------

    @ApiOperation(
        value = "Analyze the provided User-Agent",
        notes = "<b>Trying this in swagger does not work in Chrome as Chrome does not allow setting " +
                "a different User-Agent: https://github.com/swagger-api/swagger-ui/issues/5035</b>"
    )
    @PostMapping(
        value = API_BASE_PATH + "/analyze",
        consumes = TEXT_PLAIN_VALUE,
        produces = APPLICATION_JSON_VALUE
    )
    public String getJSonPOST(
        @ApiParam(
            value = "The entire POSTed value is used as the input that is to be analyzed.",
            example = EXAMPLE_USERAGENT
        )
        @RequestBody
        String userAgentString
    ) {
        return doJSon(userAgentString);
    }

    // =============== XML OUTPUT ===============

    @ApiOperation(
        value = "Analyze the provided User-Agent",
        notes = "<b>Trying this in swagger does not work in Chrome as Chrome does not allow setting " +
                "a different User-Agent: https://github.com/swagger-api/swagger-ui/issues/5035</b>"
    )
    @GetMapping(
        value = API_BASE_PATH + "/analyze",
        produces = APPLICATION_XML_VALUE
    )
    public String getXMLGET(
        @ApiParam(
            value = "The standard browser request header User-Agent is used as the input that is to be analyzed.",
            example = EXAMPLE_USERAGENT
        )
        @RequestHeader("User-Agent")
            String userAgentString
    ) {
        return doXML(userAgentString);
    }

    // -------------------------------------------------

    @ApiOperation(
        value = "Analyze the provided User-Agent",
        notes = "<b>Trying this in swagger does not work in Chrome as Chrome does not allow setting " +
                "a different User-Agent: https://github.com/swagger-api/swagger-ui/issues/5035</b>"
    )
    @PostMapping(
        value = API_BASE_PATH + "/analyze",
        consumes = TEXT_PLAIN_VALUE,
        produces = APPLICATION_XML_VALUE
    )
    public String getXMLPOST(
        @ApiParam(
            value = "The entire POSTed value is used as the input that is to be analyzed.",
            example = EXAMPLE_USERAGENT
        )
        @RequestBody String userAgentString
    ) {
        return doXML(userAgentString);
    }

    // =============== Specials ===============

    @ApiOperation(
        value = "Fire all available test cases against the analyzer to heat up the JVM"
    )
    @GetMapping(
        value = API_BASE_PATH + "/preheat",
        produces = APPLICATION_JSON_VALUE
    )
    public String getHtmlPreHeat() {
        ensureStartedForApis(OutputType.HTML);
        final int cacheSize = userAgentAnalyzer.getCacheSize();
        userAgentAnalyzer.disableCaching();
        long       start     = System.nanoTime();
        final long testsDone = userAgentAnalyzer.preHeat();
        long       stop      = System.nanoTime();
        userAgentAnalyzer.setCacheSize(cacheSize);
        if (testsDone == 0) {
            return "{ \"status\": \"No testcases available\", \"testsDone\": 0 , \"timeInMs\" : -1 } ";
        }
        return "{ \"status\": \"Ran tests\", \"testsDone\": " + testsDone + " , \"timeInMs\" : " + (stop - start) / 1000000 + " } ";
    }

    // ===========================================

    private String doHTML(String userAgentString) {
        ensureStarted();
        long start      = System.nanoTime();
        long startParse = 0;
        long stopParse  = 0;

        if (userAgentString == null) {
            throw new MissingUserAgentException();
        }

        StringBuilder sb = new StringBuilder(4096);
        try {
            sb.append("<!DOCTYPE html>");
            sb.append("<html lang=\"en\" ><head profile=\"http://www.w3.org/2005/10/profile\">");
            sb.append("<meta http-equiv='Content-Type' content='text/html; charset=UTF-8'>");
            sb.append("<meta name=viewport content=\"width=device-width, initial-scale=1\">");
            insertFavIcons(sb);
            sb.append("<meta name=\"theme-color\" content=\"dodgerblue\" />");

            // While initializing automatically reload the page.
            if (isInitializing) {
                sb.append("<meta http-equiv=\"refresh\" content=\"1\" >");
            }
            sb.append("<link rel=\"stylesheet\" href=\"style.css\">");
            sb.append("<title>Analyzing the useragent</title>");

            sb.append("</head>");
            sb.append("<body>");
            sb.append("<div class=\"header\">");
            sb.append("<h1 class=\"title\">Yet Another UserAgent Analyzer.</h1>");
            sb.append("<p class=\"version\">").append(ANALYZER_VERSION).append("</p>");
            sb.append("</div>");

            sb.append("<hr/>");

            if (userAgentAnalyzerIsAvailable) {
                startParse = System.nanoTime();
                UserAgent userAgent = userAgentAnalyzer.parse(userAgentString);
                stopParse = System.nanoTime();

                sb.append("<h2 class=\"title\">The UserAgent</h2>");
                sb.append("<p class=\"input\">").append(escapeHtml4(userAgent.getUserAgentString())).append("</p>");
                sb.append("<h2 class=\"title\">The analysis result</h2>");
                sb.append("<table id=\"result\">");
                sb.append("<tr><th colspan=2>Field</th><th>Value</th></tr>");

                Map<String, Integer>                     fieldGroupCounts = new HashMap<>();
                List<Pair<String, Pair<String, String>>> fields           = new ArrayList<>(32);
                for (String fieldname : userAgent.getAvailableFieldNamesSorted()) {
                    Pair<String, String> split = prefixSplitter(fieldname);
                    fields.add(new ImmutablePair<>(fieldname, split));
                    Integer count = fieldGroupCounts.get(split.getLeft());
                    if (count == null) {
                        count = 1;
                    } else {
                        count++;
                    }
                    fieldGroupCounts.put(split.getLeft(), count);
                }

                String currentGroup = "";
                for (Pair<String, Pair<String, String>> field : fields) {
                    String fieldname  = field.getLeft();
                    String groupName  = field.getRight().getLeft();
                    String fieldLabel = field.getRight().getRight();
                    sb.append("<tr>");
                    if (!currentGroup.equals(groupName)) {
                        currentGroup = groupName;
                        sb.append("<td rowspan=").append(fieldGroupCounts.get(currentGroup)).append("><b><u>")
                            .append(escapeHtml4(currentGroup)).append("</u></b></td>");
                    }
                    sb.append("<td>").append(camelStretcher(escapeHtml4(fieldLabel))).append("</td>")
                        .append("<td>").append(escapeHtml4(userAgent.getValue(fieldname))).append("</td>")
                        .append("</tr>");
                }
                sb.append("</table>");
                sb.append("<hr/>");

                sb.append("<p class=\"logobar documentation\">Read the online documentation at <a href=\"https://yauaa.basjes.nl\">" +
                    "https://yauaa.basjes.nl</a></p>");
                sb.append("<p class=\"logobar bug\">");
                addBugReportButton(sb, userAgent);
                sb.append("</p>");
                sb.append("<p class=\"logobar swagger\">A simple Swagger based API has been created for testing purposes: " +
                    "<a href=\"/swagger-ui.html\">Swagger UI</a></p>");

                sb.append("<p class=\"logobar source\">This project is opensource: <a href=\"https://github.com/nielsbasjes/yauaa\">" +
                    "https://github.com/nielsbasjes/yauaa</a></p>");
                sb.append("<p class=\"logobar contribute\">Creating this free software is a lot of work. " +
                    "If this has business value for your then don't hesitate to " +
                    "<a href=\"https://www.paypal.me/nielsbasjes\">contribute a little something back</a>.</p>");
                sb.append("<hr/>");
                sb.append("<form class=\"logobar tryyourown\" action=\"\" method=\"post\">");
                sb.append("<label for=\"useragent\">Manual testing of a useragent:</label>");

                sb.append("<textarea id=\"useragent\" name=\"useragent\" maxlength=\"2000\" rows=\"4\" " +
                    "placeholder=\"Paste the useragent you want to test...\">")
                    .append(escapeHtml4(userAgentString)).append("</textarea>");
                sb.append("<input class=\"testButton\" type=\"submit\" value=\"Analyze\">");
                sb.append("</form>");
                sb.append("<br/>");

                sb.append("<hr/>");
                userAgentString =
                    "Mozilla/5.0 (Linux; Android 7.8.9; nl-nl ; " +
                        "Niels Ultimate 42 demo phone Build/42 ; " +
                        "https://yauaa.basjes.nl ) " +
                        "AppleWebKit/8.4.7.2 (KHTML, like Gecko) " +
                        "Yet another browser/3.1415926 Mobile Safari/6.6.6";
                sb.append("<form class=\"logobar tryexample\" action=\"\" method=\"post\">");
                sb.append("<input type=\"hidden\" id=\"demouseragent\" name=\"useragent\" " +
                    "value=\"").append(escapeHtml4(userAgentString)).append("\">");
                sb.append("<input class=\"demoButton\" type=\"submit\" value=\"Try this demonstration UserAgent\">");
                sb.append("</form>");

                sb.append("<hr/>");

                sb.append("<hr/>");
            } else {
                long   now        = System.currentTimeMillis();
                long   millisBusy = now - initStartMoment;
                String timeString = String.format("%3.1f", ((double) millisBusy / 1000.0));

                if (userAgentAnalyzerFailureMessage == null) {
                    sb.append("<div class=\"notYetStartedBorder\">");
                    sb.append("<p class=\"notYetStarted\">The analyzer is currently starting up.</p>");
                    sb.append("<p class=\"notYetStarted\">It has been starting up for <u>").append(timeString).append("</u> seconds</p>");
                    sb.append("<p class=\"notYetStarted\">Anything between 5-15 seconds is normal.</p>");
                    sb.append("<p class=\"notYetStartedAppEngine\">On a free AppEngine 50 seconds is 'normal'.</p>");
                    sb.append("</div>");
                } else {
                    sb.append("<div class=\"failureBorder\">");
                    sb.append("<p class=\"failureContent\">The analyzer startup failed with this message</p>");
                    sb.append("<p class=\"failureContent\">").append(userAgentAnalyzerFailureMessage).append("</p>");
                    sb.append("</div>");
                }
            }
        } catch (Exception e) {
            sb.append("<div class=\"failureBorder\">");
            sb.append("<p class=\"failureContent\">An exception occurred during parsing</p>");
            sb.append("<p class=\"failureContent\">Exception: ").append(e.getClass().getSimpleName()).append("</p>");
            sb.append("<p class=\"failureContent\">Message: ").append(e.getMessage().replaceAll("\\n", "<br/>")).append("</p>");
            sb.append("</div>");
        } finally {
            long   stop              = System.nanoTime();
            double pageMilliseconds  = (stop - start) / 1000000.0;
            double parseMilliseconds = (stopParse - startParse) / 1000000.0;
            sb.append("<p class=\"speed\">Building this page took ")
                .append(String.format(Locale.ENGLISH, "%3.3f", pageMilliseconds))
                .append(" ms.</p>");

            if (parseMilliseconds > 0) {
                sb.append("<p class=\"speed\">Parsing took ")
                    .append(String.format(Locale.ENGLISH, "%3.3f", parseMilliseconds))
                    .append(" ms.</p>");
            }

            sb.append("<p class=\"speed\">").append(getMemoryUsage()).append("</p>");

            sb
                .append("<p class=\"copyright\">")
                .append(Version.COPYRIGHT.replace("Niels Basjes", "<a href=\"https://niels.basjes.nl\">Niels Basjes</a>"))
                .append("</p>")
                .append("</body>")
                .append("</html>");
        }
        return sb.toString();
    }

    private void insertFavIcons(StringBuilder sb) {
        sb.append("<link rel=\"apple-touch-icon\" href=\"/apple-touch-icon.png\">");
        sb.append("<link rel=\"icon\" type=\"image/png\" sizes=\"32x32\" href=\"/favicon-32x32.png\">");
        sb.append("<link rel=\"icon\" type=\"image/png\" sizes=\"16x16\" href=\"/favicon-16x16.png\">");
        sb.append("<link rel=\"manifest\" href=\"/manifest.json\">");
        sb.append("<link rel=\"mask-icon\" href=\"/safari-pinned-tab.svg\" color=\"#5bbad5\">");
        sb.append("<meta name=\"msapplication-TileColor\" content=\"#2d89ef\">");
        sb.append("<meta name=\"msapplication-TileImage\" content=\"/mstile-144x144.png\">");
    }

    private Pair<String, String> prefixSplitter(String input) {
        MutablePair<String, String> result = new MutablePair<>("", input);
        if (input.startsWith("Device")) {
            result.setLeft("Device");
            result.setRight(input.replaceFirst("Device", ""));
        } else if (input.startsWith("OperatingSystem")) {
            result.setLeft("Operating System");
            result.setRight(input.replaceFirst("OperatingSystem", ""));
        } else if (input.startsWith("LayoutEngine")) {
            result.setLeft("Layout Engine");
            result.setRight(input.replaceFirst("LayoutEngine", ""));
        } else if (input.startsWith("Agent")) {
            result.setLeft("Agent");
            result.setRight(input.replaceFirst("Agent", ""));
        }
        return result;
    }

    private String camelStretcher(String input) {
        String result = input.replaceAll("([A-Z])", " $1");
        result = result.replace("Device", "<b><u>Device</u></b>");
        result = result.replace("Operating System", "<b><u>Operating System</u></b>");
        result = result.replace("Layout Engine", "<b><u>Layout Engine</u></b>");
        result = result.replace("Agent", "<b><u>Agent</u></b>");
        return result.trim();
    }

    private String doJSon(String userAgentString) {
        if (userAgentString == null) {
            throw new MissingUserAgentException();
        }
        ensureStartedForApis(OutputType.JSON);
        if (userAgentAnalyzerIsAvailable) {
            UserAgent userAgent = userAgentAnalyzer.parse(userAgentString);
            return userAgent.toJson();
        }
        return "{}";
    }

    private String doXML(String userAgentString) {
        if (userAgentString == null) {
            throw new MissingUserAgentException();
        }
        ensureStartedForApis(OutputType.XML);
        if (userAgentAnalyzerIsAvailable) {
            UserAgent userAgent = userAgentAnalyzer.parse(userAgentString);
            return userAgent.toXML();
        }
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Yauaa></Yauaa>";
    }

    private void addBugReportButton(StringBuilder sb, UserAgent userAgent) {
        // https://github.com/nielsbasjes/yauaa/issues/new?title=Bug%20report&body=bar

        try {
            StringBuilder reportUrl = new StringBuilder("https://github.com/nielsbasjes/yauaa/issues/new?title=Bug%20report&body=");

            String report = "I found a problem with this useragent.\n" +
                "[Please update the output below to match what you expect it should be]\n" +
                "\n```\n" +
                userAgent.toYamlTestCase().replaceAll(" +:", "  :") +
                "\n```\n";

            reportUrl.append(URLEncoder.encode(report, "UTF-8"));
            String githubUrl = "https://github.com/login?return_to=" + URLEncoder.encode(reportUrl.toString(), "UTF-8");
            sb.append("If you find a problem with this result then please report a bug here: " +
                "<a href=\"").append(githubUrl).append("\">Yauaa issue report</a>");
        } catch (UnsupportedEncodingException e) {
            // Never happens.
        }
    }

    /**
     * <a href="https://cloud.google.com/appengine/docs/flexible/java/how-instances-are-managed#health_checking">
     * App Engine health checking</a> requires responding with 200 to {@code /_ah/health}.
     *
     * @return Returns a non empty message body.
     */
    @RequestMapping("/_ah/health")
    public String healthy() {
        // Message body required though ignored
        return "Still surviving.";
    }

    public static void main(String[] args) {
        SpringApplication.run(ParseService.class, args);
    }
}
