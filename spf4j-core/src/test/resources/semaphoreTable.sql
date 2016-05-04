
CREATE TABLE SEMAPHORES (
  SEMAPHORE_NAME VARCHAR(255) NOT NULL,
  AVAILABLE_PERMITS BIGINT(16) NOT NULL,
  TOTAL_PERMITS BIGINT(16) NOT NULL,
  LAST_UPDATED_BY VARCHAR(255) NOT NULL,
  LAST_UPDATED_AT BIGINT NOT NULL,
  PRIMARY KEY (SEMAPHORE_NAME),
  UNIQUE KEY SEMAPHORE_NAME_PK (SEMAPHORE_NAME)
);

CREATE TABLE PERMITS_BY_OWNER (
   SEMAPHORE_NAME VARCHAR(255) NOT NULL,
   OWNER VARCHAR(255) NOT NULL,
   PERMITS BIGINT(16) NOT NULL,
   LAST_UPDATED_AT BIGINT NOT NULL,
   PRIMARY KEY (SEMAPHORE_NAME, OWNER),
   UNIQUE KEY PERMITS_BY_OWNER_PK (SEMAPHORE_NAME, OWNER),
   FOREIGN KEY (SEMAPHORE_NAME) REFERENCES SEMAPHORES(SEMAPHORE_NAME)
);
