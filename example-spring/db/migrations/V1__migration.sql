-- entkt migration V1
-- CreateTable: users
-- CreateTable: posts
-- CreateTable: tags
-- AddIndex: users (email) unique
-- AddIndex: tags (name) unique
-- AddForeignKey: posts.author_id -> users.id

CREATE TABLE "users" (
  "id" uuid PRIMARY KEY,
  "name" text NOT NULL,
  "email" text NOT NULL,
  "age" integer,
  "active" boolean NOT NULL,
  "created_at" timestamptz NOT NULL,
  "updated_at" timestamptz NOT NULL
);
CREATE TABLE "posts" (
  "id" bigserial PRIMARY KEY,
  "title" text NOT NULL,
  "body" text NOT NULL,
  "published" boolean NOT NULL,
  "created_at" timestamptz NOT NULL,
  "updated_at" timestamptz NOT NULL,
  "author_id" uuid NOT NULL
);
CREATE TABLE "tags" (
  "id" serial PRIMARY KEY,
  "name" text NOT NULL,
  "category" text NOT NULL
);
CREATE UNIQUE INDEX "idx_users_email_unique" ON "users" ("email");
CREATE UNIQUE INDEX "idx_tags_name_unique" ON "tags" ("name");
ALTER TABLE "posts" ADD CONSTRAINT "fk_posts_author_id" FOREIGN KEY ("author_id") REFERENCES "users" ("id") ON DELETE RESTRICT;
