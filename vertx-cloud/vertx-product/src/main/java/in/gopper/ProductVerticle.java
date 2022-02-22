package in.gopper;

import io.vertx.config.ConfigRetriever;
import io.vertx.config.ConfigRetrieverOptions;
import io.vertx.config.ConfigStoreOptions;
import io.vertx.core.*;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.consul.*;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.ext.web.handler.CorsHandler;
import io.vertx.mysqlclient.MySQLConnectOptions;
import io.vertx.sqlclient.*;
import io.vertx.sqlclient.templates.SqlTemplate;
import io.vertx.sqlclient.templates.TupleMapper;

import java.security.SecureRandom;
import java.util.*;

/**
 * vertx product service
 *
 * @author eric
 */
public class ProductVerticle extends AbstractVerticle {

    static String verticle_deployId;

    public static void main(String[] args) {

        Vertx vertx = Vertx.vertx(new VertxOptions().setWorkerPoolSize(40));

        vertx.deployVerticle(new ProductVerticle(), new Handler<AsyncResult<String>>() {

            @Override
            public void handle(AsyncResult<String> asyncResult) {

                if (asyncResult.succeeded()) { // khi startFuture.complete() đc gọi
                    System.out.println("asyncResult = DeployId =" + asyncResult.result());

                    verticle_deployId = asyncResult.result();
                } else { //khi startFuture.fail() đc gọi
                    System.out.println("Deployment failed!");  //vì chưa đc cấp id
                }
            }
        });

    }

    private Pool pool;
    private WebClient webClient;
    private String serviceId;
    private ConsulClient consulClient;
    //    private ServiceDiscovery discovery;
    private HttpServer server;
    private SqlTemplate<Map<String, Object>, RowSet<JsonObject>> getProductTmpl;
    private SqlTemplate<JsonObject, SqlResult<Void>> addProductTmpl;

    @Override
    public void start(Promise<Void> startPromise) throws Exception {

        ConfigStoreOptions file = new ConfigStoreOptions().setType("file").setConfig(new JsonObject().put("path", "application.json"));
        ConfigRetriever retriever = ConfigRetriever.create(vertx, new ConfigRetrieverOptions().addStore(file));
        retriever.getConfig(conf -> {

            // setup 数据库
            JsonObject dbConfig = conf.result().getJsonObject("datasource");
            setUpDatabase(dbConfig);

            // setup WebClient
            setUpWebClient();

            // 注册为consul服务
            String serviceAddress = "127.0.0.1";
            Integer servicePort = conf.result().getInteger("port");
            JsonObject discoveryConfig = conf.result().getJsonObject("discovery");
            setUpConsul(serviceAddress, servicePort, discoveryConfig);

            // 注册router
            Handler<RoutingContext> getHealthRoute = ProductVerticle.this::handleGetHealth;

            Handler<RoutingContext> listUsersRoute = ProductVerticle.this::handleListUsers;

            Handler<RoutingContext> getProductRoute = ProductVerticle.this::handleGetProduct;
            Handler<RoutingContext> addProductRoute = ProductVerticle.this::handleAddProduct;
            Handler<RoutingContext> listProductsRoute = ProductVerticle.this::handleListProducts;

            Router router = Router.router(vertx);

            router.route().handler(CorsHandler.create("*").allowedHeaders(allowedHeaders()).allowedMethods(allowedMethods()));

            router.get("/health").handler(getHealthRoute);

            router.get("/users").handler(listUsersRoute);

            router.get("/products/:productID").handler(getProductRoute);
            router.post("/products").handler(addProductRoute);
            router.get("/products").handler(listProductsRoute);

            server = vertx.createHttpServer();
            server.requestHandler(router);
            server.listen(conf.result().getInteger("port"), res -> {
                if (res.succeeded()) {
                    startPromise.complete();
                } else {
                    startPromise.fail(res.cause());
                }
            });

        });

    }

    @Override
    public void stop(Promise<Void> stopPromise) throws Exception {

        System.out.println("ProductVerticle.stop (async):" + serviceId);

        consulClient.deregisterService(serviceId, res -> {
            if (res.succeeded()) {
                System.out.println("Service successfully deregistered");
            } else {
                System.out.println("Service error deregistered");
                res.cause().printStackTrace();
            }

            try {
                super.stop(stopPromise);
            } catch (Exception e) {
                e.printStackTrace();
            }

        });

    }

    private void handleGetHealth(RoutingContext routingContext) {
        HttpServerResponse response = routingContext.response();
        String rep = new JsonObject().put("status", "UP").toString();
        response
                .putHeader("content-type", "application/json")
                .end(rep);
    }

    private void handleListUsers(RoutingContext routingContext) {

        HttpServerResponse response = routingContext.response();

        // 使用descovery获取Record
//        discovery.getRecord(new JsonObject().put("name", "consul-demo-consumer"), ar1 -> {
//
//            if (ar1.succeeded() && ar1.result() != null) {
//                System.out.println("=============" + ar1.result());
//                //                // 以get方式请求远程地址
//                webClient.get(ar1.result().getLocation().getInteger("port"), ar1.result().getLocation().getString("host"), "/user/list")
//                        .putHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:65.0) Gecko/20100101 Firefox/65.0")
//                        .addQueryParam("username", "admin")
//                        .send(handle -> {
//                            // 处理响应的结果
//                            if (handle.succeeded()) {
//                                // 这里拿到的结果就是一个HTML文本，直接打印出来
//                                String body = handle.result().bodyAsString();
//                                System.out.println(body);
//                                response
//                                        .putHeader("content-type", "application/json")
//                                        .end(body);
//                            }
//                        });
//            }
//        });

        //use consul client get services
        consulClient.healthServiceNodes("consul-demo-consumer", true, res -> {
            if (res.succeeded()) {
                System.out.println("found " + res.result().getList().size() + " services");
                System.out.println("consul state index: " + res.result().getIndex());

                List<ServiceEntry> lsService = res.result().getList();
                if (null == lsService || 0 == lsService.size()) {
                    String rep = new JsonObject().put("status", "UP").toString();
                    response
                            .putHeader("content-type", "application/json")
                            .end(rep);
                    return;
                }

                Random random = new SecureRandom();
                int n = random.nextInt(lsService.size());
                ServiceEntry entry = lsService.get(n);
                if (null == entry) {
                    String rep = new JsonObject().put("status", "UP").toString();
                    response
                            .putHeader("content-type", "application/json")
                            .end(rep);
                    return;
                }

                System.out.println("Service node: " + entry.getNode());
                System.out.println("Service address: " + entry.getService().getAddress());
                System.out.println("Service port: " + entry.getService().getPort());

                // 以get方式请求远程地址
                webClient.get(entry.getService().getPort(), entry.getService().getAddress(), "/user/list")
                        .putHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:65.0) Gecko/20100101 Firefox/65.0")
                        .addQueryParam("username", "admin")
                        .send(handle -> {
                            // 处理响应的结果
                            if (handle.succeeded()) {
                                // 这里拿到的结果就是一个HTML文本，直接打印出来
                                String body = handle.result().bodyAsString();
                                System.out.println(body);
                                response
                                        .putHeader("content-type", "application/json")
                                        .end(body);
                            }
                        });

            } else {
                String rep = new JsonObject().put("status", "UP").toString();
                response
                        .putHeader("content-type", "application/json")
                        .write(rep);
                response.end();
            }
        });

    }

    private void handleGetProduct(RoutingContext routingContext) {
        String productID = routingContext.request().getParam("productID");
        HttpServerResponse response = routingContext.response();
        if (productID == null) {
            routingContext.fail(400);
        } else {
            getProductTmpl
                    .execute(Collections.singletonMap("id", productID))
                    .onSuccess(result -> {
                        if (result.size() == 0) {
                            routingContext.fail(404);
                        } else {
                            response
                                    .putHeader("content-type", "application/json")
                                    .end(result.iterator().next().encode());
                        }
                    }).onFailure(err -> {
                routingContext.fail(500);
            });
        }
    }

    private void handleAddProduct(RoutingContext routingContext) {

        HttpServerResponse response = routingContext.response();

        JsonObject product = routingContext.getBodyAsJson();

        addProductTmpl.execute(product)
                .onSuccess(res -> response.end())
                .onFailure(err -> routingContext.fail(500));
    }

    private void handleListProducts(RoutingContext routingContext) {
        HttpServerResponse response = routingContext.response();

        pool.query("SELECT id, nick_name, price, weight FROM products limit 0,10").execute(query -> {
            if (query.failed()) {
                routingContext.fail(500);
            } else {
                JsonArray arr = new JsonArray();
                query.result().forEach(row -> {
                    arr.add(row.toJson());
                });
                routingContext.response().putHeader("content-type", "application/json").end(arr.encode());
            }
        });
    }

    /**
     * setUp database
     *
     * @param dbConfig
     */
    private void setUpDatabase(JsonObject dbConfig) {
        MySQLConnectOptions options = new MySQLConnectOptions()
                .setPort(dbConfig.getInteger("port"))
                .setHost(dbConfig.getString("host"))
                .setDatabase(dbConfig.getString("db_name"))
                .setUser(dbConfig.getString("user"))
                .setPassword(dbConfig.getString("password"));

        pool = Pool.pool(vertx, options, new PoolOptions().setMaxSize(4));

        getProductTmpl = SqlTemplate
                .forQuery(pool, "SELECT id, nick_name, price, weight FROM products where id = #{id} LIMIT 1")
                .mapTo(Row::toJson);

        addProductTmpl = SqlTemplate
                .forUpdate(pool, "INSERT INTO products (nick_name, price, weight) VALUES (#{name}, #{price}, #{weight})")
                .mapFrom(TupleMapper.jsonObject());

    }

    /**
     * setUp consul 服务
     *
     * @param serviceAddress
     * @param servicePort
     * @param discoveryConfig
     */
    private void setUpConsul(String serviceAddress, Integer servicePort, JsonObject discoveryConfig) {

        serviceId = discoveryConfig.getString("serviceId");

        ConsulClientOptions optConsul = new ConsulClientOptions()
                .setHost(discoveryConfig.getString("host"))
                .setPort(discoveryConfig.getInteger("port"));

        consulClient = ConsulClient.create(vertx, optConsul);

        CheckOptions optsCheck = new CheckOptions()
                .setHttp(discoveryConfig.getString("health"))
                .setInterval("5s");

        ServiceOptions opts = new ServiceOptions()
                .setName(discoveryConfig.getString("serviceName"))
                .setId(serviceId)
                .setTags(Arrays.asList("tag", "port" + servicePort))
                .setCheckOptions(optsCheck)
                .setAddress(serviceAddress)
                .setPort(servicePort);

        consulClient.registerService(opts, res -> {
            if (res.succeeded()) {
                System.out.println("VertxService successfully registered:" + serviceId);
            } else {
                res.cause().printStackTrace();
            }
        });

        // 使用discovery机制
//            discovery = ServiceDiscovery.create(vertx);
//            discovery.registerServiceImporter(new ConsulServiceImporter(),
//                    new JsonObject()
//                            .put("host", "localhost")
//                            .put("port", 8500)
//                            .put("scan-period", 2000));
    }

    /**
     * setUp WebClient
     */
    private void setUpWebClient() {
        // 创建WebClient，用于发送HTTP或者HTTPS请求,ms
        WebClientOptions webClientOptions = new WebClientOptions()
                .setConnectTimeout(5000);

        webClient = WebClient.create(vertx, webClientOptions);
    }

    /**
     * cors allowed headers
     *
     * @return
     */
    private Set<String> allowedHeaders() {

        Set<String> allowedHeaders = new HashSet<>();
        allowedHeaders.add("x-requested-with");
        allowedHeaders.add("Access-Control-Allow-Origin");
        allowedHeaders.add("origin");
        allowedHeaders.add("Content-Type");
        allowedHeaders.add("accept");
        allowedHeaders.add("X-PINGARUNER");

        return allowedHeaders;
    }

    /**
     * cors allowed methods
     *
     * @return
     */
    private Set<HttpMethod> allowedMethods() {
        Set<HttpMethod> allowedMethods = new HashSet<>();

        allowedMethods.add(HttpMethod.GET);
        allowedMethods.add(HttpMethod.POST);
        allowedMethods.add(HttpMethod.OPTIONS);

        allowedMethods.add(HttpMethod.DELETE);
        allowedMethods.add(HttpMethod.PATCH);
        allowedMethods.add(HttpMethod.PUT);

        return allowedMethods;
    }
}
