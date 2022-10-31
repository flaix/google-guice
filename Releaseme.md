Releasing a new gitblit-guice version
-------------------------------------

To create and release a new version of the guice servlet with Gitblit's changes,
follow these steps. For example, the last Guice release was 5.1.0 and the latest
one is 5.2.0:

Rebase the `issue-807` branch onto the new Guice release tag.
`$ git rebase --onto 5.2.0 5.1.0`

Adjust the code so that it works again. (Compile, test, the lot.)
You will also have to adjust the version number of the guice-servlet
artifact, since that is changed to have the Gitblit specific version.
```
$ sed -i -e "s/4.2.3-gb2/5.1.0-gb2/" pom.xml
$ git diff
diff --git a/extensions/servlet/pom.xml b/extensions/servlet/pom.xml
index b113076c4..d38b6ecc5 100644
--- a/extensions/servlet/pom.xml
+++ b/extensions/servlet/pom.xml
@@ -10,7 +10,7 @@
   </parent>

   <artifactId>guice-servlet</artifactId>
-  <version>4.2.3-gb2</version>
+  <version>5.1.0-gb2</version>

   <name>Google Guice - Extensions - Servlet</name>

$ git commit -a --amend
```


Then merge the `issue-807` branch into the `gitblit-guice` branch. This will
result in various conflicts, because our patches are based on the release
commit. But nothing is changed in the code on the `guice-gitblit` branch,
so you can resolve all conflicts with the code coming from the `issue-807`
branch. After all, that branch carries the code you want to release.
`$ git merge -X theirs issue-807`

In order for Maven/Moxie to work properly, the two versions in the servlet POM,
which use properties, should be replaced with fixed versions. This is the Guice
version, so in the example replace `${project.parent.version}` with `5.2.0`.
Commit this change, amending the merge commit.

For example:
```
$ sed -i -e 's/${project.parent.version}/5.2.0/' extensions/servlet/pom.xml
$ git diff
diff --git a/extensions/servlet/pom.xml b/extensions/servlet/pom.xml
index 3c20b18e2..2eb221928 100644
--- a/extensions/servlet/pom.xml
+++ b/extensions/servlet/pom.xml
@@@ -27,6 -27,38 +27,38 @@@
     <dependency>
       <groupId>com.google.inject</groupId>
       <artifactId>guice</artifactId>
-      <version>${project.parent.version}</version>
+      <version>5.2.0</version>
     </dependency>
     <dependency>
       <groupId>com.google.inject</groupId>
       <artifactId>guice</artifactId>
-      <version>${project.parent.version}</version>
+      <version>5.2.0</version>
       <classifier>tests</classifier>
       <scope>test</scope>
     </dependency>

$ git commit -a --amend
```

Push the branch to Github. Pushing the branch will run an action that builds
the servlet. Should this action fail, you will have to check what went wrong.

If everything went smooth, create an annotated tag in the format `<version>-gb<patchversion>`
on the `gitblit-guice` branch and push it to Github. Pushing the tag will
trigger a Github workflow which deploys the artifacts to the `gitblit-maven`
repository.

```
$ git tag -a -m "Gitblit adjusted version 5.2.0-gb2 of guice-servlet" 5.2.0-gb2
$ git push origin 5.2.0-gb2
```
