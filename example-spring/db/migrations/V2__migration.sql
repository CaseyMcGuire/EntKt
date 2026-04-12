-- entkt migration V2
-- CreateTable: friendships
-- AddIndex: friendships (requester_id, recipient_id) unique
-- AddForeignKey: friendships.requester_id -> users.id
-- AddForeignKey: friendships.recipient_id -> users.id

CREATE TABLE "friendships" (
  "id" serial PRIMARY KEY,
  "status" text NOT NULL,
  "requester_id" uuid NOT NULL,
  "recipient_id" uuid NOT NULL
);
CREATE UNIQUE INDEX "idx_friendships_requester_id_recipient_id_unique" ON "friendships" ("requester_id", "recipient_id");
ALTER TABLE "friendships" ADD CONSTRAINT "fk_friendships_requester_id" FOREIGN KEY ("requester_id") REFERENCES "users" ("id") ON DELETE RESTRICT;
ALTER TABLE "friendships" ADD CONSTRAINT "fk_friendships_recipient_id" FOREIGN KEY ("recipient_id") REFERENCES "users" ("id") ON DELETE RESTRICT;
