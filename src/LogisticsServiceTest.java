import java.nio.file.Path;

public final class LogisticsServiceTest {
    public static void main(String[] args) throws Exception {
        ShipmentRecord missingProof = new ShipmentRecord("SHP-1042", "DELIVERED", "A. Chen", "2026-10-24T09:30:00Z", null);
        LogisticsService service = new LogisticsService(null, "test", "test.pdf");
        String result = service.process(missingProof);
        if (!"EXCEPTION_REVIEW".equals(result)) throw new AssertionError(result);
        System.out.println("PASS: delivered shipment without proof enters EXCEPTION_REVIEW");
    }
}
