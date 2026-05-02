-- entkt migration V3
-- CreateTable: post_tags
-- AddIndex: post_tags (post_id, tag_id) unique
-- AddForeignKey: post_tags.post_id -> posts.id
-- AddForeignKey: post_tags.tag_id -> tags.id

CREATE TABLE "post_tags" (
  "id" serial PRIMARY KEY,
  "post_id" bigint NOT NULL,
  "tag_id" integer NOT NULL
);
CREATE UNIQUE INDEX "idx_post_tags_post_id_tag_id_unique" ON "post_tags" ("post_id", "tag_id");
ALTER TABLE "post_tags" ADD CONSTRAINT "fk_post_tags_post_id" FOREIGN KEY ("post_id") REFERENCES "posts" ("id") ON DELETE RESTRICT;
ALTER TABLE "post_tags" ADD CONSTRAINT "fk_post_tags_tag_id" FOREIGN KEY ("tag_id") REFERENCES "tags" ("id") ON DELETE RESTRICT;
