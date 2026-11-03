import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

record ShipmentRecord(String shipmentId, String status, String recipient, String deliveredAt, Path proofOfDelivery) {}

final class LogisticsService {
    private final InfraiClient client;
    private final String bucket;
    private final String archiveKey;

    LogisticsService(InfraiClient client, String bucket, String archiveKey) { this.client = client; this.bucket = bucket; this.archiveKey = archiveKey; }

    String process(ShipmentRecord shipment) throws IOException, InterruptedException {
        if ("DELIVERED".equals(shipment.status()) && shipment.proofOfDelivery() == null) return "EXCEPTION_REVIEW";
        String fields = "{\"shipment_id\":\"" + shipment.shipmentId() + "\",\"recipient\":\"" + shipment.recipient() + "\",\"delivered_at\":\"" + shipment.deliveredAt() + "\",\"exception\":\"NONE\"}";
        byte[] input = Files.readAllBytes(shipment.proofOfDelivery());
        byte[] flattenedPdf = client.fillAndFlatten(Base64.getEncoder().encodeToString(input), fields);
        client.archive(bucket, archiveKey, Base64.getEncoder().encodeToString(flattenedPdf));
        return "ARCHIVED";
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("usage: LogisticsService <pdf-file>");
        String recipient = System.getenv("INFRAI_RECIPIENT");
        if (recipient == null || recipient.isBlank()) {
            throw new IllegalArgumentException("INFRAI_RECIPIENT must be set explicitly");
        }
        String apiKey = System.getenv("INFRAI_API_KEY");
        if (apiKey == null || apiKey.isBlank()) throw new IllegalArgumentException("INFRAI_API_KEY must be set");
        String baseUrl = System.getenv().getOrDefault("INFRAI_BASE_URL", InfraiClient.DEFAULT_BASE_URL);
        String bucket = System.getenv().getOrDefault("INFRAI_BUCKET", "logistics-forms");
        String archiveKey = System.getenv().getOrDefault("INFRAI_ARCHIVE_KEY", "shipments/latest-flattened.pdf");
        LogisticsService service = new LogisticsService(new InfraiClient(apiKey, baseUrl), bucket, archiveKey);
        ShipmentRecord shipment = new ShipmentRecord("SHP-DEMO", "DELIVERED", recipient,
                java.time.Instant.now().toString(), Path.of(args[0]));
        System.out.println(service.process(shipment));
    }
}
