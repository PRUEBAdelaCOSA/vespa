// Copyright Vespa.ai. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.controller.restapi.pricing;

import com.yahoo.collections.Pair;
import com.yahoo.component.annotation.Inject;
import com.yahoo.config.provision.ClusterResources;
import com.yahoo.config.provision.NodeResources;
import com.yahoo.container.jdisc.HttpRequest;
import com.yahoo.container.jdisc.HttpResponse;
import com.yahoo.container.jdisc.ThreadedHttpRequestHandler;
import com.yahoo.restapi.ErrorResponse;
import com.yahoo.restapi.Path;
import com.yahoo.restapi.SlimeJsonResponse;
import com.yahoo.slime.Cursor;
import com.yahoo.slime.Slime;
import com.yahoo.text.Text;
import com.yahoo.vespa.hosted.controller.Controller;
import com.yahoo.vespa.hosted.controller.api.integration.billing.Plan;
import com.yahoo.vespa.hosted.controller.api.integration.pricing.ApplicationResources;
import com.yahoo.vespa.hosted.controller.api.integration.pricing.PriceInformation;
import com.yahoo.vespa.hosted.controller.api.integration.pricing.PricingInfo;
import com.yahoo.vespa.hosted.controller.restapi.ErrorResponses;
import com.yahoo.yolean.Exceptions;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.logging.Logger;

import static com.yahoo.jdisc.http.HttpRequest.Method.GET;
import static com.yahoo.restapi.ErrorResponse.methodNotAllowed;
import static com.yahoo.vespa.hosted.controller.api.integration.pricing.PricingInfo.SupportLevel;
import static java.lang.Double.parseDouble;
import static java.lang.Integer.parseInt;
import static java.math.BigDecimal.ZERO;
import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * API for calculating price information
 *
 * @author hmusum
 */
@SuppressWarnings("unused") // Handler
public class PricingApiHandler extends ThreadedHttpRequestHandler {

    private static final Logger log = Logger.getLogger(PricingApiHandler.class.getName());

    private final Controller controller;

    @Inject
    public PricingApiHandler(Context parentCtx, Controller controller) {
        super(parentCtx);
        this.controller = controller;
    }

    @Override
    public HttpResponse handle(HttpRequest request) {
        if (request.getMethod() != GET)
            return methodNotAllowed("Method '" + request.getMethod() + "' is not supported");

        try {
            return handleGET(request);
        } catch (IllegalArgumentException e) {
            return ErrorResponse.badRequest(Exceptions.toMessageString(e));
        } catch (RuntimeException e) {
            return ErrorResponses.logThrowing(request, log, e);
        }
    }

    private HttpResponse handleGET(HttpRequest request) {
        Path path = new Path(request.getUri());
        if (path.matches("/pricing/v1/pricing")) return pricing(request);

        return ErrorResponse.notFoundError(Text.format("No '%s' handler at '%s'", request.getMethod(),
                                                       request.getUri().getPath()));
    }

    private HttpResponse pricing(HttpRequest request) {
        String rawQuery = request.getUri().getRawQuery();
        var priceParameters = parseQuery(rawQuery);
        PriceInformation price = calculatePrice(priceParameters);
        return response(price, priceParameters);
    }

    private PriceInformation calculatePrice(PriceParameters priceParameters) {
        var priceCalculator = controller.serviceRegistry().pricingController();
        if (priceParameters.appResources == null)
            return priceCalculator.price(priceParameters.clusterResources, priceParameters.pricingInfo, priceParameters.plan);
        else
            return priceCalculator.priceForApplications(priceParameters.appResources, priceParameters.pricingInfo, priceParameters.plan);
    }

    private PriceParameters parseQuery(String rawQuery) {
        if (rawQuery == null) throw new IllegalArgumentException("No price information found in query");
        List<String> elements = Arrays.stream(URLDecoder.decode(rawQuery, UTF_8).split("&")).toList();

        if (keysAndValues(elements).stream().map(Pair::getFirst).toList().contains("resources"))
            return parseQueryLegacy(elements);
        else
            return parseQuery(elements);
    }

    private PriceParameters parseQueryLegacy(List<String> elements) {
        var supportLevel = SupportLevel.BASIC;
        var enclave = false;
        var committedSpend = ZERO;
        var plan = controller.serviceRegistry().planRegistry().defaultPlan(); // fallback to default plan if not supplied
        List<ClusterResources> clusterResources = new ArrayList<>();

        for (Pair<String, String> entry : keysAndValues(elements)) {
            var value = entry.getSecond();
            switch (entry.getFirst().toLowerCase()) {
                case "committedspend" -> committedSpend = new BigDecimal(value);
                case "enclave" -> enclave = Boolean.parseBoolean(value);
                case "planid" -> plan = plan(value).orElseThrow(() -> new IllegalArgumentException("Unknown plan id " + value));
                case "supportlevel" -> supportLevel = SupportLevel.valueOf(value.toUpperCase());
                case "resources" -> clusterResources.add(clusterResources(value));
                default -> throw new IllegalArgumentException("Unknown query parameter '" + entry.getFirst() + '\'');
            }
        }
        if (clusterResources.isEmpty()) throw new IllegalArgumentException("No cluster resources found in query");

        PricingInfo pricingInfo = new PricingInfo(enclave, supportLevel, committedSpend.doubleValue());
        return new PriceParameters(clusterResources, pricingInfo, plan, null);
    }

    private PriceParameters parseQuery(List<String> elements) {
        var supportLevel = SupportLevel.BASIC;
        var enclave = false;
        var committedSpend = ZERO;
        var applicationName = "default";
        var plan = controller.serviceRegistry().planRegistry().defaultPlan(); // fallback to default plan if not supplied
        List<ApplicationResources> appResources = new ArrayList<>();

        for (Pair<String, String> entry : keysAndValues(elements)) {
            var value = entry.getSecond();
            switch (entry.getFirst().toLowerCase()) {
                case "committedspend" -> committedSpend = new BigDecimal(value);
                case "planid" -> plan = plan(value).orElseThrow(() -> new IllegalArgumentException("Unknown plan id " + value));
                case "supportlevel" -> supportLevel = SupportLevel.valueOf(value.toUpperCase());
                case "application" -> appResources.add(applicationResources(value));
                default -> throw new IllegalArgumentException("Unknown query parameter '" + entry.getFirst() + '\'');
            }
        }
        if (appResources.isEmpty()) throw new IllegalArgumentException("No application resources found in query");

        // TODO: enclave does not make sense in PricingInfo anymore, remove when legacy method is removed
        PricingInfo pricingInfo = new PricingInfo(false, supportLevel, committedSpend.doubleValue());
        return new PriceParameters(List.of(), pricingInfo, plan, appResources);
    }

    private ClusterResources clusterResources(String resourcesString) {
        List<String> elements = Arrays.stream(resourcesString.split(",")).toList();

        var nodes = 0;
        var vcpu = 0d;
        var memoryGb = 0d;
        var diskGb = 0d;
        var gpuMemoryGb = 0d;

        for (var element : keysAndValues(elements)) {
            var value = element.getSecond();
            switch (element.getFirst().toLowerCase()) {
                case "nodes" -> nodes = parseInt(value);
                case "vcpu" -> vcpu = parseDouble(value);
                case "memorygb" -> memoryGb = parseDouble(value);
                case "diskgb" -> diskGb = parseDouble(value);
                case "gpumemorygb" -> gpuMemoryGb = parseDouble(value);
                default -> throw new IllegalArgumentException("Unknown resource type '" + element.getFirst() + '\'');
            }
        }

        var nodeResources = new NodeResources(vcpu, memoryGb, diskGb, 0); // 0 bandwidth, not used in price calculation
        if (gpuMemoryGb > 0)
            nodeResources = nodeResources.with(new NodeResources.GpuResources(1, gpuMemoryGb));
        return new ClusterResources(nodes, 1, nodeResources);
    }

    private ApplicationResources applicationResources(String appResourcesString) {
        List<String> elements = Arrays.stream(appResourcesString.split(",")).toList();

        var applicationName = "default";
        var vcpu = ZERO;
        var memoryGb = ZERO;
        var diskGb = ZERO;
        var gpuMemoryGb = ZERO;
        var enclaveVcpu = ZERO;
        var enclaveMemoryGb = ZERO;
        var enclaveDiskGb = ZERO;
        var enclaveGpuMemoryGb = ZERO;

        for (var element : keysAndValues(elements)) {
            var value = element.getSecond();
            switch (element.getFirst().toLowerCase()) {
                case "name" -> applicationName = value;

                case "vcpu" -> vcpu = new BigDecimal(value);
                case "memorygb" -> memoryGb = new BigDecimal(value);
                case "diskgb" -> diskGb = new BigDecimal(value);
                case "gpumemorygb" -> gpuMemoryGb = new BigDecimal(value);

                case "enclavevcpu" -> enclaveVcpu = new BigDecimal(value);
                case "enclavememorygb" -> enclaveMemoryGb = new BigDecimal(value);
                case "enclavediskgb" -> enclaveDiskGb = new BigDecimal(value);
                case "enclavegpumemorygb" -> enclaveGpuMemoryGb = new BigDecimal(value);

                default -> throw new IllegalArgumentException("Unknown key '" + element.getFirst() + '\'');
            }
        }

        return new ApplicationResources(applicationName, vcpu, memoryGb, diskGb, gpuMemoryGb,
                                        enclaveVcpu, enclaveMemoryGb, enclaveDiskGb, enclaveGpuMemoryGb);
    }

    private List<Pair<String, String>> keysAndValues(List<String> elements) {
        return elements.stream().map(element -> {
                    var index = element.indexOf("=");
                    if (index <= 0 || index == element.length() - 1)
                        throw new IllegalArgumentException("Error in query parameter, expected '=' between key and value: '" + element + '\'');
                    return new Pair<>(element.substring(0, index), element.substring(index + 1));
                })
                .toList();
    }

    private Optional<Plan> plan(String element) {
        return controller.serviceRegistry().planRegistry().plan(element);
    }

    private static SlimeJsonResponse response(PriceInformation priceInfo, PriceParameters priceParameters) {
        var slime = new Slime();
        Cursor cursor = slime.setObject();

        var array = cursor.setArray("priceInfo");
        addItem(array, supportLevelDescription(priceParameters), priceInfo.listPriceWithSupport());
        addItem(array, "Enclave", priceInfo.enclaveDiscount());
        addItem(array, "Volume discount", priceInfo.volumeDiscount());
        addItem(array, "Committed spend", priceInfo.committedAmountDiscount());

        setBigDecimal(cursor, "totalAmount", priceInfo.totalAmount());

        return new SlimeJsonResponse(slime);
    }

    private static String supportLevelDescription(PriceParameters priceParameters) {
        String supportLevel = priceParameters.pricingInfo.supportLevel().name();
        return supportLevel.substring(0,1).toUpperCase() + supportLevel.substring(1).toLowerCase() + " support unit price";
    }

    private static void addItem(Cursor array, String name, BigDecimal amount) {
        if (amount.compareTo(BigDecimal.ZERO) != 0) {
            var o = array.addObject();
            o.setString("description", name);
            setBigDecimal(o, "amount", amount);
        }
    }

    private static void setBigDecimal(Cursor cursor, String name, BigDecimal value) {
        cursor.setString(name, value.setScale(2, RoundingMode.HALF_UP).toPlainString());
    }

    private record PriceParameters(List<ClusterResources> clusterResources, PricingInfo pricingInfo, Plan plan,
                                   List<ApplicationResources> appResources) {

    }

}