plugins { id("meridian.spring-service") }

meridianService {
    basePackage = "io.meridian.notification"
    database = "notification_db"
}

dependencies {
    // EMAIL channel over SMTP (JavaMailSender). Without an SMTP host the sandbox adapter records deliveries instead.
    implementation("org.springframework.boot:spring-boot-starter-mail")
}
