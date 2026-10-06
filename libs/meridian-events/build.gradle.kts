plugins { id("meridian.library-conventions") }

dependencies {
    api(project(":libs:meridian-common"))
    api("org.springframework.boot:spring-boot-starter-jdbc")
    api("org.springframework.boot:spring-boot-starter-kafka")
    api("org.springframework.boot:spring-boot-starter-jackson")
}
